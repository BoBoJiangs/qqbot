package top.sshh.qqbot.verifycode;

import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.data.VerifyCount;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Captures the API feedback emitted by the game receipt handler. */
class RecordingStatisticsClient extends CaptchaStatisticsClient {
    final VerifyCount counts = new VerifyCount();
    final Map<String, String> outcomes = new HashMap<>();
    final Map<String, String> reasons = new HashMap<>();
    private final Map<String, String> models = new HashMap<>();
    private final Map<String, LocalDate> dates = new HashMap<>();
    private final Set<String> settled = new HashSet<>();

    @Override public void trackRecognition(RecognitionResult result, String model, long botId) {
        if (result == null || result.getRecognitionId() == null || models.containsKey(result.getRecognitionId())) { return; }
        String id = result.getRecognitionId();
        models.put(id, model);
        dates.put(id, counts.recordAttempt(model));
    }

    @Override public void reportOutcome(RecognitionResult result, String outcome, String reason) {
        if (result == null || !models.containsKey(result.getRecognitionId()) || !settled.add(result.getRecognitionId())) { return; }
        String id = result.getRecognitionId();
        outcomes.put(id, outcome);
        reasons.put(id, reason);
        if (!"unconfirmed".equals(outcome)) { counts.recordOutcome(models.get(id), "success".equals(outcome), dates.get(id)); }
    }
}
