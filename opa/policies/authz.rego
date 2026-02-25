package authz

import future.keywords.if
import future.keywords.in

default allow := false

# Same-tenant access is always allowed
allow if {
    input.actorTid == input.ownerTid
}

# System tools are accessible to all authenticated users
allow if {
    input.ownerTid == "system"
}

# Cross-tenant access requires a valid grant (checked by GrantEngine in Java)
# This policy focuses on additional attribute-based rules

# Allow if caller has admin scope
allow if {
    "mcp:tools-admin" in input.scopes
}

# Allow if caller has basic scope and tool is not restricted
allow if {
    "mcp:tools-basic" in input.scopes
    not is_restricted_tool(input.toolName)
}

# Restricted tools require explicit admin scope
is_restricted_tool(name) if {
    name in {"workflow_run", "plan_execute", "memory_clear"}
}

# Deny if source type is upstream and no scopes present
deny if {
    input.sourceType in {"upstream_mcp", "upstream_rest"}
    count(input.scopes) == 0
}
