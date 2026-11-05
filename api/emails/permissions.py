from rest_framework import permissions


class EmailPermissionClass(permissions.BasePermission):

    def has_permission(self, request, view):
        if request.method in permissions.SAFE_METHODS:
            return request.user.has_perm('emails.view_emails')

        if request.method == 'POST':
            return request.user.has_perm('emails.add_emails')

        if request.method == 'DELETE':
            return request.user.has_perm('emails.delete_emails')

        return False
