from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0002_email_context_and_security")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["-imported_at", "-gmail_id"], name="emails_imported_gmail_idx",
            ),
        ),
    ]
