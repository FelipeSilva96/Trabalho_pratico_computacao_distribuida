"""Cliente manual de teste para os PARTICIPANTES 2PC, nao coordenador duravel.

Exemplos:
  python /app/replica_rpc_cli.py prepare --worker slic-worker-lubuntu:50051 --tx demo-tx --result demo-png --file /tmp/result.png
  python /app/replica_rpc_cli.py status  --worker slic-worker-lubuntu:50051 --tx demo-tx
  python /app/replica_rpc_cli.py commit  --worker slic-worker-lubuntu:50051 --tx demo-tx
  python /app/replica_rpc_cli.py read    --worker slic-worker-lubuntu:50051 --result demo-png --out /tmp/read.png
"""
import argparse
import hashlib
from pathlib import Path
import sys

import grpc
import classifier_pb2 as pb
import classifier_pb2_grpc as rpc


def main():
    p=argparse.ArgumentParser(description='Cliente gRPC para validar as replicas; NAO substitui o coordenador 2PC')
    p.add_argument('action',choices=['prepare','commit','abort','status','read'])
    p.add_argument('--worker',required=True)
    p.add_argument('--tx',default='tx-demo-001')
    p.add_argument('--result',default='result-demo-001')
    p.add_argument('--file')
    p.add_argument('--out')
    opts=p.parse_args()
    with grpc.insecure_channel(opts.worker,options=[('grpc.max_receive_message_length',16*1024*1024)]) as ch:
        c=rpc.ClassifierServiceStub(ch)
        if opts.action=='prepare':
            if not opts.file:
                p.error('prepare requer --file')
            data=Path(opts.file).read_bytes()
            r=c.PrepareReplica(pb.PrepareReplicaRequest(transaction_id=opts.tx,result_id=opts.result,
                 image_png=data,sha256=hashlib.sha256(data).hexdigest(),logical_time=1),timeout=20)
            print(f'{r.worker_id}: {r.state} tx={r.transaction_id} lamport={r.logical_time}')
        elif opts.action=='commit':
            r=c.CommitReplica(pb.TransactionDecisionRequest(transaction_id=opts.tx,logical_time=1),timeout=20)
            print(f'{r.worker_id}: {r.state} tx={r.transaction_id} lamport={r.logical_time}')
        elif opts.action=='abort':
            r=c.AbortReplica(pb.TransactionDecisionRequest(transaction_id=opts.tx,logical_time=1),timeout=20)
            print(f'{r.worker_id}: {r.state} tx={r.transaction_id} lamport={r.logical_time}')
        elif opts.action=='status':
            r=c.ReplicaStatus(pb.ReplicaStatusRequest(transaction_id=opts.tx,logical_time=1),timeout=10)
            print(f'{r.worker_id}: {r.state} tx={r.transaction_id} result={r.result_id} sha256={r.sha256} lamport={r.logical_time}')
        elif opts.action=='read':
            r=c.ReadReplica(pb.ReadReplicaRequest(result_id=opts.result,logical_time=1),timeout=20)
            sha=hashlib.sha256(r.image_png).hexdigest()
            if sha!=r.sha256:
                raise ValueError('Hash remoto nao corresponde ao PNG recebido')
            print(f'{r.worker_id}: READ OK {len(r.image_png)} bytes sha256={sha} lamport={r.logical_time}')
            if opts.out:
                Path(opts.out).write_bytes(r.image_png)
                print(f'Salvo em {opts.out}')


if __name__=='__main__':
    try: main()
    except grpc.RpcError as e:
        print(f'RPC ERROR: {e.code().name}: {e.details()}',file=sys.stderr)
        sys.exit(1)
