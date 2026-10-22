import os
import ssl
import unittest
from unittest.mock import patch
from tamaq.auth.email import SMTPDelivery


class EmailAdapterTest(unittest.TestCase):
    def test_production_rejects_local_mailpit_even_with_tls(self):
        with patch.dict(os.environ,{'TAMAQ_SMTP_HOST':'mailpit','TAMAQ_EMAIL_FROM':'sender@example.com','TAMAQ_SMTP_STARTTLS':'true'},clear=True):
            with self.assertRaises(RuntimeError): SMTPDelivery('production')

    def test_production_requires_sender_and_tls(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError): SMTPDelivery('production')
        with patch.dict(os.environ, {'TAMAQ_SMTP_HOST':'smtp.example.com',
                                    'TAMAQ_EMAIL_FROM':'sender@example.com'}, clear=True):
            with self.assertRaises(RuntimeError): SMTPDelivery('production')

    def test_starttls_verifies_certificate_and_hostname(self):
        with patch.dict(os.environ, {'TAMAQ_SMTP_HOST':'smtp.example.com',
                                    'TAMAQ_EMAIL_FROM':'sender@example.com',
                                    'TAMAQ_SMTP_PORT':'587', 'TAMAQ_SMTP_STARTTLS':'true'}, clear=True):
            with patch('tamaq.auth.email.smtplib.SMTP') as factory:
                SMTPDelivery('production').send('recipient@example.com','verify','test-code')
                smtp=factory.return_value.__enter__.return_value
                context=smtp.starttls.call_args.kwargs['context']
                self.assertEqual(ssl.CERT_REQUIRED, context.verify_mode)
                self.assertTrue(context.check_hostname)
                smtp.send_message.assert_called_once()


if __name__=='__main__': unittest.main()
