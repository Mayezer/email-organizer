from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("emails", "0010_email_sync_pending_index")]

    operations = [
        migrations.CreateModel(
            name="EmailSyncCheckpoint",
            fields=[
                ("account", models.TextField(primary_key=True, serialize=False)),
                ("history_id", models.TextField()),
            ],
            options={"db_table": "email_sync_checkpoints"},
        ),
        migrations.AddField(
            model_name="emailsyncjob",
            name="full_history_id",
            field=models.TextField(null=True),
        ),
        migrations.AddField(
            model_name="emailsyncjob",
            name="history_offset",
            field=models.PositiveIntegerField(db_default=0),
        ),
        migrations.AddField(
            model_name="emailsyncjob",
            name="history_recovery_started",
            field=models.BooleanField(db_default=False),
        ),
    ]
