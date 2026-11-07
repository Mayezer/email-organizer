from django.core import signing
from django.db import connection
from rest_framework.exceptions import ValidationError


SALT = "emails.imports.v1"


def latest_revision():
    with connection.cursor() as cursor:
        cursor.execute("SELECT revision FROM email_import_checkpoint WHERE singleton")
        return cursor.fetchone()[0]


def encode_revision(revision):
    return signing.dumps(revision, salt=SALT)


def decode_revision(value, parameter):
    try:
        return signing.loads(value, salt=SALT)
    except signing.BadSignature as exc:
        raise ValidationError({parameter: "Cursor de importação inválido."}) from exc
