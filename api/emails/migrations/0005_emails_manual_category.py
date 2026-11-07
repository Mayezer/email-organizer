from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("emails", "0004_email_category_page_index")]

    operations = [
        migrations.AddField(
            model_name="emails",
            name="manual_category",
            field=models.TextField(blank=True, null=True),
        ),
    ]
