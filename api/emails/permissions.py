from django.conf import settings
from rest_framework import permissions


class EmailAccountPermission(permissions.BasePermission):
    """Authorize the server-side account selected by the App."""

    def has_permission(self, request, view):
        account = request.headers.get("X-Email-Account")
        service_username = settings.EMAIL_APP_SERVICE_USERNAME
        request.email_account = None
        if not service_username and account is None:
            return True
        if (not account or account != settings.EMAIL_SYNC_ACCOUNT
                or not service_username or request.user.get_username() != service_username):
            return False
        request.email_account = account
        return True


class EmailPermissionClass(permissions.BasePermission):

    def has_permission(self, request, view):
        if request.method in permissions.SAFE_METHODS:
            return request.user.has_perm('emails.view_emails')

        if request.method == 'POST':
            return request.user.has_perm('emails.add_emails')

        if request.method == 'PATCH':
            return request.user.has_perm('emails.change_emails')

        if request.method == 'DELETE':
            return request.user.has_perm('emails.delete_emails')

        return False
