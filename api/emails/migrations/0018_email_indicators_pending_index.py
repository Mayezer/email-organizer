from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0017_email_indicators")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["gmail_id"],
                condition=models.Q(has_attachments__isnull=True) | models.Q(has_links__isnull=True),
                name="emails_indicators_pending_idx",
            ),
        ),
    ]
