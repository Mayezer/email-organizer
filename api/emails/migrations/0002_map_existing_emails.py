from django.db import migrations, models
import django.utils.timezone


class Migration(migrations.Migration):
    dependencies = [
        ('emails', '0001_initial'),
    ]

    operations = [
        migrations.SeparateDatabaseAndState(
            database_operations=[],
            state_operations=[
                migrations.DeleteModel(name='Emails'),
                migrations.CreateModel(
                    name='Emails',
                    fields=[
                        ('gmail_id', models.TextField(primary_key=True, serialize=False)),
                        ('sender', models.TextField(blank=True, null=True)),
                        ('subject', models.TextField(blank=True, null=True)),
                        ('processing_status', models.CharField(default='PENDING', max_length=20)),
                        ('imported_at', models.DateTimeField(default=django.utils.timezone.now)),
                        ('processed_at', models.DateTimeField(blank=True, null=True)),
                        ('category', models.TextField(blank=True, null=True)),
                        ('summary', models.TextField(blank=True, null=True)),
                        ('requires_action', models.BooleanField(blank=True, null=True)),
                    ],
                    options={
                        'managed': False,
                        'db_table': 'emails',
                    },
                ),
            ],
        ),
    ]
