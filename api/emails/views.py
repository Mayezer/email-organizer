from rest_framework import generics

from rest_framework.permissions import IsAuthenticated

from emails.models import Emails
from emails.permissions import EmailPermissionClass
from emails.serializers import EmailSerializers


class EmailListCreateView(generics.ListCreateAPIView):

    permission_classes = (IsAuthenticated, EmailPermissionClass)
    queryset = Emails.objects.all()
    serializer_class = EmailSerializers


class EmailRetriaveDestroyView(generics.RetrieveDestroyAPIView):

    permission_classes = (IsAuthenticated, EmailPermissionClass)
    queryset = Emails.objects.all()
    serializer_class = EmailSerializers
