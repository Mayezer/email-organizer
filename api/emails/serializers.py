from rest_framework import serializers

from emails.models import Emails


class EmailSerializers(serializers.ModelSerializer):
    class Meta:
        model = Emails
        fields = '__all__'
        
