from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0012_email_processing_updates")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["update_revision"], condition=models.Q(update_revision__isnull=False),
                name="emails_update_revision_idx",
            ),
        ),
    ]
