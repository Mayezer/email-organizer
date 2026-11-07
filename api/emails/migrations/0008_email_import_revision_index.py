from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0007_email_import_revision")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["import_revision"], condition=models.Q(import_revision__isnull=False),
                name="emails_import_revision_idx",
            ),
        ),
    ]
