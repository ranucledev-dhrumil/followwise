package com.followwise.workspace;

import java.util.Set;

public enum Permission {
    VIEW_WORKSPACE(Set.of(Role.OWNER, Role.STAFF)),
    VIEW_MEMBERS(Set.of(Role.OWNER));

    private final Set<Role> grantedRoles;

    Permission(Set<Role> grantedRoles) {
        this.grantedRoles = grantedRoles;
    }

    public boolean isGrantedTo(Role role) {
        return role != null && grantedRoles.contains(role);
    }
}
