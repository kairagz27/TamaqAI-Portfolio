import importlib.util, json, tempfile, unittest
from pathlib import Path

source=Path(__file__).resolve().parents[1]/'scripts/pilot_preflight.py'
spec=importlib.util.spec_from_file_location('pilot_preflight',source)
preflight=importlib.util.module_from_spec(spec);spec.loader.exec_module(preflight)

class PilotPreflightTest(unittest.TestCase):
    def test_missing_config_reports_names_only(self):
        with tempfile.TemporaryDirectory() as root:
            report=preflight.inspect(Path(root)/'missing.env')
        self.assertFalse(report['ready']); self.assertIn('OPENAI_API_KEY',report['missing_fields'])

    def test_compose_database_password_mismatch_and_fake_provider_rejected(self):
        text=(Path(__file__).resolve().parents[1]/'.env.auth-production.example').read_text()
        values={'POSTGRES_PASSWORD':'synthetic-preflight-password','TAMAQ_DATABASE_URL':'postgresql://tamaq:synthetic-preflight-password@postgres:5432/tamaq',
                'TAMAQ_JWT_SECRET':'synthetic-preflight-jwt-key-'+'x'*32,'TAMAQ_DOMAIN':'pilot.example.com','TAMAQ_ACME_EMAIL':'fixture@example.com',
                'TAMAQ_SMTP_HOST':'smtp.example.com','TAMAQ_SMTP_USERNAME':'fixture','TAMAQ_SMTP_PASSWORD':'synthetic-smtp-password',
                'TAMAQ_EMAIL_FROM':'fixture@example.com','OPENAI_API_KEY':'synthetic-provider-key','OPENAI_MODEL':'fixture',
                'PILOT_INVITE_TOKENS':json.dumps(['synthetic-preflight-invite-'+'x'*32])}
        text='\n'.join(line.split('=',1)[0]+'='+values[line.split('=',1)[0]] if '=' in line and line.split('=',1)[0] in values else line for line in text.splitlines())
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'fixture.env';path.write_text(text)
            self.assertTrue(preflight.inspect(path)['ready'])
            path.write_text(text.replace('TAMAQ_PROVIDER=openai','TAMAQ_PROVIDER=fake').replace('POSTGRES_PASSWORD=synthetic-preflight-password','POSTGRES_PASSWORD=wrong-fixture'))
            report=preflight.inspect(path)
            self.assertFalse(report['ready']);self.assertIn('postgres_url_and_password_must_match_compose',report['configuration_errors'])
            self.assertNotIn('synthetic-preflight-password',json.dumps(report))
