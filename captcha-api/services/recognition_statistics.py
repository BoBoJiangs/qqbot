"""Durable cross-client recognition outcomes, with one settlement per API ticket."""
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from functools import lru_cache
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import sqlite3
import time
import uuid

from pydantic import BaseModel, ConfigDict, Field
from typing import Literal

from config import settings

BEIJING = timezone(timedelta(hours=8))


class StatisticsContext(BaseModel):
    model_config = ConfigDict(extra='forbid')
    version: Literal[1] = 1
    serverId: str = Field(pattern=r'^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')
    serverName: str = Field(default='', max_length=80, pattern=r'^[^\x00-\x1f\x7f]*$')


class StatisticsError(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


class RecognitionStatistics:
    def __init__(self, path, key_path):
        self.path, self.key_path = Path(path), Path(key_path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS servers (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at REAL NOT NULL, last_seen REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS recognitions (
                    id TEXT PRIMARY KEY, token_hash TEXT NOT NULL, server_id TEXT NOT NULL,
                    qq TEXT NOT NULL, recognizer TEXT NOT NULL CHECK(recognizer IN ('primary','knn')),
                    created_at REAL NOT NULL, outcome TEXT CHECK(outcome IN ('success','failure','unconfirmed')),
                    reason TEXT, settled_at REAL);
                CREATE INDEX IF NOT EXISTS recognition_scope ON recognitions(server_id,qq,created_at);
                CREATE INDEX IF NOT EXISTS recognition_qq ON recognitions(qq,created_at);
                CREATE INDEX IF NOT EXISTS recognition_time ON recognitions(created_at);
                CREATE TABLE IF NOT EXISTS image_upload_fallbacks (
                    recognition_id TEXT PRIMARY KEY, url_hash TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0);
            ''')
            db.execute('INSERT OR IGNORE INTO metadata VALUES (?,?)', ('epoch',uuid.uuid4().hex))
            db.execute('INSERT OR IGNORE INTO metadata VALUES (?,?)', ('started_at',str(time.time())))

    @contextmanager
    def connect(self):
        db = sqlite3.connect(str(self.path), timeout=5)
        db.row_factory = sqlite3.Row
        try:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('PRAGMA busy_timeout=5000')
            with db:
                yield db
        finally:
            db.close()

    def read_key(self):
        self.key_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            descriptor = os.open(self.key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            pass
        else:
            with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
                output.write(secrets.token_urlsafe(32))
        key = self.key_path.read_text(encoding='utf-8').strip()
        if len(key) < 32:
            raise StatisticsError('统计查询密钥文件无效', 503)
        return key

    def authorize_read(self, supplied):
        return bool(supplied) and hmac.compare_digest(self.read_key().encode(), supplied.encode())

    def start(self, context, recognizer, qq):
        if recognizer not in {'primary','knn'} or not qq or not qq.isdigit() or len(qq) > 20:
            raise StatisticsError('统计来源或 QQ 无效')
        ticket, token, now = uuid.uuid4().hex, secrets.token_urlsafe(32), time.time()
        name = context.serverName.strip() or context.serverId
        with self.connect() as db:
            db.execute('INSERT INTO servers VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen',
                       (context.serverId,name,now,now))
            db.execute('INSERT INTO recognitions (id,token_hash,server_id,qq,recognizer,created_at) VALUES (?,?,?,?,?,?)',
                       (ticket,hashlib.sha256(token.encode()).hexdigest(),context.serverId,qq,recognizer,now))
        return {'recognitionId':ticket,'feedbackToken':token}

    def allow_image_upload(self, ticket, url):
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO image_upload_fallbacks (recognition_id,url_hash) VALUES (?,?)',
                       (ticket, hashlib.sha256(url.encode()).hexdigest()))

    def claim_image_upload(self, ticket, token, url, qq):
        """One image upload may continue an authorized, unresolved download failure."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT r.*,f.url_hash,f.used FROM recognitions r '
                             'JOIN image_upload_fallbacks f ON r.id=f.recognition_id WHERE r.id=?', (ticket,)).fetchone()
            if row is None:
                raise StatisticsError('本次识别不支持原图上传兜底', 404)
            if not hmac.compare_digest(row['token_hash'], hashlib.sha256(token.encode()).hexdigest()) \
                    or row['qq'] != qq or row['url_hash'] != hashlib.sha256(url.encode()).hexdigest():
                raise StatisticsError('原图上传凭据或来源无效', 403)
            if row['outcome'] is not None or row['used']:
                raise StatisticsError('本次识别已结算或已使用上传兜底', 409)
            if time.time() - row['created_at'] > 300:
                raise StatisticsError('原图上传凭据已过期', 410)
            db.execute('UPDATE image_upload_fallbacks SET used=1 WHERE recognition_id=?', (ticket,))
            return row['recognizer']

    def settle(self, ticket, token, outcome, reason):
        if outcome not in {'success','failure','unconfirmed'}:
            raise StatisticsError('统计结果无效')
        reasons = {'success':{'game_accepted'},
                   'failure':{'game_rejected','same_image_again','no_usable_answer','same_failed_answer'},
                   'unconfirmed':{'service_error','client_abandoned','client_restart','feedback_timeout','superseded',
                                  'captcha_expired','manual_takeover','click_error'}}
        if reason not in reasons[outcome]:
            raise StatisticsError('统计原因与结果不匹配')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM recognitions WHERE id=?',(ticket,)).fetchone()
            if row is None:
                raise StatisticsError('识别记录不存在或统计已重置',404)
            if not hmac.compare_digest(row['token_hash'],hashlib.sha256(token.encode()).hexdigest()):
                raise StatisticsError('结果上报凭据无效',403)
            if row['outcome'] is not None:
                if row['outcome'] != outcome:
                    raise StatisticsError('该识别已按其他结果结算',409)
                return {'code':0,'duplicate':True}
            db.execute('UPDATE recognitions SET outcome=?,reason=?,settled_at=? WHERE id=? AND outcome IS NULL',
                       (outcome,reason,time.time(),ticket))
            db.execute('DELETE FROM image_upload_fallbacks WHERE recognition_id=?', (ticket,))
        return {'code':0,'duplicate':False}

    def summary(self, server_id=None, qq=None):
        now = datetime.now(BEIJING)
        start = now.replace(hour=0,minute=0,second=0,microsecond=0).timestamp()
        end = start + 86400
        where, values = [], []
        if server_id:where.append('server_id=?');values.append(server_id)
        if qq:where.append('qq=?');values.append(qq)
        clause = ' WHERE ' + ' AND '.join(where) if where else ''
        with self.connect() as db:
            db.execute('BEGIN')
            if server_id and db.execute('SELECT 1 FROM servers WHERE id=?',(server_id,)).fetchone() is None:
                raise StatisticsError('服务器不存在，请发送“验证统计 服务器列表”查看编号',404)
            grouped = db.execute('''SELECT recognizer,COUNT(*) AS attempts,
                SUM(outcome='success') AS successes,SUM(outcome='failure') AS failures,
                SUM(created_at>=? AND created_at<?) AS today_attempts,
                SUM(created_at>=? AND created_at<? AND outcome='success') AS today_successes,
                SUM(created_at>=? AND created_at<? AND outcome='failure') AS today_failures
                FROM recognitions''' + clause + ' GROUP BY recognizer', [start,end,start,end,start,end]+values).fetchall()
            scope = db.execute('SELECT COUNT(DISTINCT server_id),COUNT(DISTINCT qq) FROM recognitions'+clause,values).fetchone()
            metadata = dict(db.execute('SELECT name,value FROM metadata').fetchall())
            server = db.execute('SELECT name FROM servers WHERE id=?',(server_id,)).fetchone() if server_id else None
        result = {'scope':{'server_id':server_id,'server_name':server['name'] if server else None,'qq':qq},
                  'source_servers':scope[0],'source_qq':scope[1], 'date':now.date().isoformat(), 'time_zone':'+08:00',
                  'epoch':metadata['epoch'],'started_at':float(metadata['started_at']), 'models':{}}
        rows = {row['recognizer']:dict(row) for row in grouped}
        for model in ['primary','knn']:
            row = rows.get(model,{})
            result['models'][model] = {}
            for period,prefix in [('total',''),('today','today_')]:
                attempts,successes,failures = [int(row.get(prefix+name) or 0) for name in ['attempts','successes','failures']]
                result['models'][model][period] = {'attempts':attempts,'successes':successes,'failures':failures,
                    'unconfirmed':attempts-successes-failures,'success_rate':round(successes*100/(successes+failures),2) if successes+failures else 0.}
        result['text'] = self.format_summary(result)
        return result

    @staticmethod
    def format_summary(data):
        scope = data['scope']
        title = '验证码验证统计（全局）' if not scope['server_id'] and not scope['qq'] else '验证码验证统计（筛选）'
        text = title + '\n统计范围：' + (scope['server_id'] or '全部服务器') + ' · ' + (('QQ '+scope['qq']) if scope['qq'] else '全部 QQ')
        text += '\n统计来源：'+str(data['source_servers'])+' 台 Java 服务 · '+str(data['source_qq'])+' 个 QQ'
        for model,label in [('primary','主模型'),('knn','KNN 重试')]:
            text += '\n\n'+label
            for period,name in [('total','累计'),('today','今日')]:
                s=data['models'][model][period]
                text += ('\n%s：调用 %d，成功 %d，失败 %d，未确认 %d，成功率 %.2f%%' %
                         (name,s['attempts'],s['successes'],s['failures'],s['unconfirmed'],s['success_rate']))
        return text

    def servers(self):
        with self.connect() as db:
            rows = db.execute('''SELECT s.id,s.name,s.last_seen,COUNT(r.id) AS attempts,COUNT(DISTINCT r.qq) AS qq_count
                FROM servers s LEFT JOIN recognitions r ON r.server_id=s.id GROUP BY s.id ORDER BY s.created_at''').fetchall()
        items = [dict(row) for row in rows]
        text = '验证码统计服务器列表\n' + ('暂无已接入的 Java 服务' if not items else '\n'.join(
            row['id']+' · '+row['name']+' · '+str(row['qq_count'])+' 个 QQ' for row in items[:50]))
        if len(items)>50:text+='\n其余服务器请在后台查看'
        return {'items':items,'text':text}

    def reset(self, expected_epoch):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            epoch = db.execute("SELECT value FROM metadata WHERE name='epoch'").fetchone()[0]
            if epoch != expected_epoch:
                raise StatisticsError('统计版本已变化，请刷新后重试',409)
            db.execute('DELETE FROM image_upload_fallbacks')
            db.execute('DELETE FROM recognitions')
            db.execute("UPDATE metadata SET value=? WHERE name='epoch'",(uuid.uuid4().hex,))
            db.execute("UPDATE metadata SET value=? WHERE name='started_at'",(str(time.time()),))
        return self.summary()


@lru_cache(maxsize=1)
def get_recognition_statistics():
    return RecognitionStatistics(settings.recognition_statistics_file, settings.recognition_statistics_key_file)
