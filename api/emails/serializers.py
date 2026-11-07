from rest_framework import serializers

from emails.models import Emails, EmailSyncJob


class EmailSerializers(serializers.ModelSerializer):
    class Meta:
        model = Emails
        exclude = ("manual_category", "import_revision", "update_revision", "sync_account")


class EmailManualCategorySerializer(serializers.Serializer):
    category = serializers.ChoiceField(choices=(
        "professional opportunities and recruitment",
        "event invitations",
        "social media updates",
        "advertising",
        "other",
    ))


class EmailMetadataSerializer(serializers.ModelSerializer):
    effective_category = serializers.SerializerMethodField()
    model_fields = ("gmail_id", "thread_id", "sender", "subject", "received_at", "imported_at",
                    "processing_status", "category", "manual_category")

    def get_effective_category(self, email):
        return email.manual_category if email.manual_category is not None else email.category or ""

    class Meta:
        model = Emails
        fields = ("gmail_id", "thread_id", "sender", "subject", "received_at", "imported_at",
                  "processing_status", "effective_category")


class EmailSyncJobSerializer(serializers.ModelSerializer):
    class Meta:
        model = EmailSyncJob
        fields = ("id", "status", "imported_count", "analyzed_count", "analysis_failed_count", "created_at", "updated_at")
