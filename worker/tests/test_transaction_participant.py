import hashlib
import io
import tempfile
import unittest
from pathlib import Path

from PIL import Image
from transaction_participant import TwoPhaseParticipant, TransactionConflict, TransactionCorrupted


def png(color):
    out=io.BytesIO()
    Image.new('RGB',(20,20),color).save(out,format='PNG')
    return out.getvalue()


class ParticipantTests(unittest.TestCase):
    def setUp(self):
        self.t=tempfile.TemporaryDirectory()
        self.p=TwoPhaseParticipant(self.t.name)
        self.data=png('green')
        self.sha=hashlib.sha256(self.data).hexdigest()

    def tearDown(self):
        self.t.cleanup()

    def test_prepare_nao_publica(self):
        self.assertEqual(self.p.prepare('tx-1','res-1',self.data,self.sha),'PREPARED')
        with self.assertRaises(KeyError): self.p.read_committed('res-1')

    def test_commit_publica_e_idempotente(self):
        self.p.prepare('tx-1','res-1',self.data,self.sha)
        self.assertEqual(self.p.commit('tx-1'),'COMMITTED')
        self.assertEqual(self.p.commit('tx-1'),'COMMITTED')
        self.assertEqual(self.p.read_committed('res-1'),self.data)

    def test_abort_e_idempotente(self):
        self.p.prepare('tx-1','res-1',self.data,self.sha)
        self.assertEqual(self.p.abort('tx-1'),'ABORTED')
        self.assertEqual(self.p.abort('tx-1'),'ABORTED')
        with self.assertRaises(KeyError): self.p.read_committed('res-1')
        with self.assertRaises(TransactionConflict): self.p.commit('tx-1')

    def test_restart_prepared_e_commit(self):
        self.p.prepare('tx-1','res-1',self.data,self.sha)
        p2=TwoPhaseParticipant(self.t.name)
        self.assertEqual(p2.status('tx-1')['state'],'PREPARED')
        self.assertEqual(p2.commit('tx-1'),'COMMITTED')
        self.assertEqual(TwoPhaseParticipant(self.t.name).read_committed('res-1'),self.data)

    def test_restart_committed(self):
        self.p.prepare('tx-1','res-1',self.data,self.sha)
        self.p.commit('tx-1')
        self.assertEqual(TwoPhaseParticipant(self.t.name).status('tx-1')['state'],'COMMITTED')

    def test_abort_desconhecida_cria_tombstone(self):
        self.assertEqual(self.p.abort('tx-late'),'ABORTED')
        with self.assertRaises(TransactionConflict): self.p.prepare('tx-late','res-late',self.data,self.sha)

    def test_2pc_rejeita_sha_divergente(self):
        with self.assertRaises(ValueError): self.p.prepare('tx-1','res-1',self.data,'0'*64)

    def test_reuse_de_id_divergente(self):
        self.p.prepare('tx-1','res-1',self.data,self.sha)
        other=png('red')
        with self.assertRaises(TransactionConflict): self.p.prepare('tx-1','res-1',other,hashlib.sha256(other).hexdigest())
        with self.assertRaises(TransactionConflict): self.p.prepare('tx-2','res-1',other,hashlib.sha256(other).hexdigest())

    def test_proibido_abort_apos_commit(self):
        self.p.prepare('tx-1','res-1',self.data,self.sha)
        self.p.commit('tx-1')
        with self.assertRaises(TransactionConflict): self.p.abort('tx-1')

    def test_prepared_corroto_detectado(self):
        self.p.prepare('tx-1','res-1',self.data,self.sha)
        (Path(self.t.name)/'prepared'/'tx-1.png').write_bytes(b'corrupted')
        with self.assertRaises(TransactionCorrupted): self.p.commit('tx-1')

    def test_commit_sem_prepare_e_rejeitado(self):
        with self.assertRaises(KeyError): self.p.commit('tx-404')


if __name__=='__main__': unittest.main()
