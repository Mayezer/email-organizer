from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models
from django.db.models.functions import Coalesce


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0005_emails_manual_category")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                Coalesce("manual_category", "category", models.Value(""), output_field=models.TextField()),
                models.F("imported_at").desc(),
                models.F("gmail_id").desc(),
                name="emails_effective_page_idx",
            ),
        ),
    ]
