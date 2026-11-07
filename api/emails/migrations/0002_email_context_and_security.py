from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("emails", "0001_initial")]

    operations = [
        migrations.AddField(
            model_name="emails",
            name="thread_id",
            field=models.TextField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name="emails",
            name="received_at",
            field=models.DateTimeField(blank=True, null=True),
        ),
        migrations.AddField(
            model_name="emails",
            name="security_status",
            field=models.CharField(
                max_length=20,
                choices=[
                    ("NO_ALERT", "No Alert"),
                    ("SUSPICIOUS", "Suspicious"),
                    ("INCONCLUSIVE", "Inconclusive"),
                ],
                blank=True,
                null=True,
            ),
        ),
        migrations.AddField(
            model_name="emails",
            name="security_reason",
            field=models.TextField(blank=True, null=True),
        ),
        migrations.AddIndex(
            model_name="emails",
            index=models.Index(
                fields=["thread_id", "received_at"], name="emails_thread_received_idx",
            ),
        ),
        migrations.AddIndex(
            model_name="emails",
            index=models.Index(fields=["received_at"], name="emails_received_idx"),
        ),
    ]
