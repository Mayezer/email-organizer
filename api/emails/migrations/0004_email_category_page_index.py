from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models
from django.db.models.functions import Coalesce


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0003_email_pagination_index")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                Coalesce("category", models.Value(""), output_field=models.TextField()),
                models.F("imported_at").desc(),
                models.F("gmail_id").desc(),
                name="emails_category_page_idx",
            ),
        ),
    ]
