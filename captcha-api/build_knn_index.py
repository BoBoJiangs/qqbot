"""Build a deployable KNN artifact from confirmed annotations, without changing them."""
import argparse
import hashlib
import json
from pathlib import Path
from threading import Lock

import cv2
import numpy as np

from knn_classifier import crop_pair,extract_features,fingerprint


def build(dataset_root, model_root, output_root):
    root,models,output = Path(dataset_root).resolve(),Path(model_root),Path(output_root)
    config,weights = models/'darknet.cfg',models/'darknet_last.weights'
    allowed = set((models/'labels.txt').read_text(encoding='utf-8').splitlines())
    network = cv2.dnn.readNetFromDarknet(np.frombuffer(config.read_bytes(),np.uint8),
                                      np.frombuffer(weights.read_bytes(),np.uint8))
    import re
    text=config.read_text(encoding='utf-8').split('[net]',1)[1].split('[',1)[0]
    input_size=tuple(int(re.search(r'^\s*'+name+r'\s*=\s*(\d+)',text,re.M).group(1)) for name in ['width','height'])
    features,labels=[],[]
    documents=0
    for path in sorted((root/'annotations').glob('*.json')):
        annotation=json.loads(path.read_text(encoding='utf-8'))
        if annotation.get('ground_truth_confirmed') is not True:
            continue
        source=(root/annotation['image']['path']).resolve()
        if not source.is_relative_to(root):
            raise ValueError('原图路径越界')
        image=cv2.imdecode(np.frombuffer(source.read_bytes(),np.uint8),cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError(f'原图无法解码：{path.name}')
        pairs=[]
        for item in annotation['items']:
            if item['label'] not in allowed:
                raise ValueError(f'标签无效：{path.name}')
            pairs.append(crop_pair(image,item['box'])); labels.append(item['label'])
        if pairs:
            features.append(extract_features(network,Lock(),input_size,pairs));documents+=1
    if not features:
        raise ValueError('没有已确认样本')
    matrix=np.concatenate(features,axis=0)
    output.mkdir(parents=True,exist_ok=True)
    index=output/'reference_index.npz'
    np.savez_compressed(index,features=matrix,labels=np.asarray(labels))
    report={'source':'human_confirmed_annotations','documents':documents,'candidates':len(labels),
        'reference_labels':sorted(set(labels)),'feature_dim':int(matrix.shape[1]),
        'model_assets':fingerprint(config,weights),'index_sha256':hashlib.sha256(index.read_bytes()).hexdigest(),
        'threshold':{'similarity_threshold':.5,'margin_threshold':0.,'vote_threshold':0.},
        'threshold_note':'Reuses existing reviewed KNN gates; no new accuracy claim.'}
    (output/'reference_meta.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--dataset-root',required=True)
    parser.add_argument('--model-root',default='models')
    parser.add_argument('--output-root',default='knn')
    args=parser.parse_args()
    print(json.dumps(build(args.dataset_root,args.model_root,args.output_root),ensure_ascii=False))
