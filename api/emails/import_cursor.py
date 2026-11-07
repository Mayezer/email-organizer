from django.core import signing
from django.db import connection
from rest_framework.exceptions import ValidationError


SALT = "emails.imports.v1"


def latest_revision():
    with connection.cursor() as cursor:
        cursor.execute("SELECT revision FROM email_import_checkpoint WHERE singleton")
        return cursor.fetchone()[0]


def revision_salt(account):
    return SALT if account is None else f"{SALT}.account:{account}"


def encode_revision(revision, account=None):
    return signing.dumps(revision, salt=revision_salt(account))


def decode_revision(value, parameter, account=None):
    try:
        return signing.loads(value, salt=revision_salt(account))
    except signing.BadSignature as exc:
        raise ValidationError({parameter: "Cursor de importação inválido."}) from exc
