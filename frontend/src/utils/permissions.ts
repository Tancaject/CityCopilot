import type { UserDTO } from '../types';

const rolePermissions: Record<string, string[]> = {
  admin: ['*'],
  geo_operator: ['admin:access', 'geo:read', 'geo:write', 'audit:read'],
  auditor: ['admin:access', 'geo:read', 'audit:read'],
  merchant: ['admin:access', 'shop:write', 'voucher:write', 'order:write', 'booking:manage', 'booking:check-in'],
};

type PermissionSubject = string | UserDTO | null | undefined;

function permissionsOf(subject: PermissionSubject) {
  if (!subject) {
    return [];
  }
  if (typeof subject === 'string') {
    return rolePermissions[subject] || [];
  }
  return subject.permissions?.length ? subject.permissions : rolePermissions[subject.role || ''] || [];
}

export function hasPermission(subject: PermissionSubject, permission: string) {
  const permissions = permissionsOf(subject);
  if (!permissions.length) {
    return false;
  }
  return permissions.includes('*') || permissions.includes(permission);
}

export function hasAllPermissions(subject: PermissionSubject, permissions: string[]) {
  if (!subject) {
    return false;
  }
  return permissions.every((permission) => hasPermission(subject, permission));
}

export function hasRole(role: string | undefined) {
  if (!role) {
    return false;
  }
  return Boolean(rolePermissions[role]);
}
