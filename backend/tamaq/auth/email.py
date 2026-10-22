"""Delivery adapter. Local SMTP goes to Mailpit; production must explicitly enable TLS."""
import os
import smtplib
import ssl
from email.message import EmailMessage

class SMTPDelivery:
    def __init__(self, environment):
        self.host = os.environ.get('TAMAQ_SMTP_HOST', '')
        self.port = int(os.environ.get('TAMAQ_SMTP_PORT', '1025'))
        self.sender = os.environ.get('TAMAQ_EMAIL_FROM', '')
        self.tls = os.environ.get('TAMAQ_SMTP_STARTTLS', 'false') == 'true'
        self.username = os.environ.get('TAMAQ_SMTP_USERNAME', '')
        self.password = os.environ.get('TAMAQ_SMTP_PASSWORD', '')
        if not self.host or not self.sender or (environment == 'production' and (not self.tls or self.host.lower() in ('mailpit','localhost','127.0.0.1','::1'))):
            raise RuntimeError('Auth requires a configured sender; production requires external SMTP with STARTTLS')

    def send(self, recipient, purpose, token):
        message = EmailMessage()
        message['From'] = self.sender; message['To'] = recipient
        message['Subject'] = 'TamaqAI — подтверждение email' if purpose == 'verify' else 'TamaqAI — восстановление пароля'
        message.set_content('Введите одноразовый код в TamaqAI:\n\n' + token + '\n\n' +
                            ('Код действует 24 часа.' if purpose == 'verify' else 'Код действует 30 минут.'))
        with smtplib.SMTP(self.host, self.port, timeout=10) as smtp:
            if self.tls: smtp.starttls(context=ssl.create_default_context())
            if self.username: smtp.login(self.username, self.password)
            smtp.send_message(message)

class MemoryDelivery:
    """Only for explicitly injected tests; never chosen by runtime configuration."""
    def __init__(self): self.messages = []
    def send(self, recipient, purpose, token): self.messages.append((recipient, purpose, token))
