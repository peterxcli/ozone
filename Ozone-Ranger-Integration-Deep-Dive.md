# Apache Ozone & Apache Ranger Integration: A Deep Dive

## Table of Contents
1. [Overview & Architecture](#1-overview--architecture)
2. [Authorization Plugin System](#2-authorization-plugin-system)
3. [Ranger Client Integration](#3-ranger-client-integration)
4. [Policy Management Deep-Dive](#4-policy-management-deep-dive)
5. [Role Management Deep-Dive](#5-role-management-deep-dive)
6. [Background Synchronization Mechanism](#6-background-synchronization-mechanism)
7. [Multi-Tenancy Integration](#7-multi-tenancy-integration)
8. [Configuration Guide](#8-configuration-guide)
9. [Code Flow Examples](#9-code-flow-examples)
10. [Deployment Considerations](#10-deployment-considerations)

---

## 1. Overview & Architecture

Apache Ozone integrates with Apache Ranger to provide centralized, policy-based authorization for object storage operations. This integration enables:

- **Centralized Policy Management**: All authorization policies managed through Ranger UI/API
- **Fine-grained Access Control**: Policies at volume, bucket, and key levels
- **Multi-tenancy Support**: S3-compatible multi-tenant access with Ranger-backed authorization
- **Audit Trail**: Complete audit logging through Ranger
- **Role-based Access**: Ranger roles for user and admin management

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Apache Ozone Manager                     │
│                                                              │
│  ┌────────────────┐         ┌──────────────────────────┐   │
│  │  Client Request│────────>│  IAccessAuthorizer       │   │
│  └────────────────┘         │  Interface               │   │
│                              └──────────┬───────────────┘   │
│                                         │                    │
│                    ┌────────────────────┴──────────────┐   │
│                    │                                     │   │
│         ┌──────────▼────────────┐      ┌───────────────▼───┐│
│         │ RangerOzoneAuthorizer │      │OzoneNativeAuthorizer││
│         │  (Ranger Plugin)      │      │  (Built-in)        ││
│         └──────────┬────────────┘      └────────────────────┘│
│                    │                                          │
│         ┌──────────▼────────────┐                            │
│         │RangerClientMultiTenant │                           │
│         │  AccessController      │                           │
│         └──────────┬────────────┘                            │
│                    │                                          │
│         ┌──────────▼────────────┐                            │
│         │  RangerClient         │                            │
│         │  (HTTP Client)        │                            │
│         └──────────┬────────────┘                            │
└────────────────────┼─────────────────────────────────────────┘
                     │ HTTPS
                     │
         ┌───────────▼────────────┐
         │   Apache Ranger        │
         │   Admin Server         │
         │                        │
         │  - Policies            │
         │  - Roles               │
         │  - Audit Logs          │
         └────────────────────────┘
```

### Key Components

1. **IAccessAuthorizer Interface** (`hadoop-ozone/common/src/main/java/org/apache/hadoop/ozone/security/acl/IAccessAuthorizer.java`)
   - Core authorization abstraction
   - Single method: `checkAccess(IOzoneObj ozoneObject, RequestContext context)`
   - Supports pluggable authorization implementations

2. **RangerOzoneAuthorizer** (Ranger Plugin)
   - Ranger-specific implementation of IAccessAuthorizer
   - Delegates to Ranger for policy evaluation
   - Configured via: `ozone.acl.authorizer.class=org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer`

3. **OzoneNativeAuthorizer** (`hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/security/acl/OzoneNativeAuthorizer.java:45`)
   - Built-in Ozone authorization
   - Hierarchical permission checking (volume → bucket → key)
   - Admin bypass support

4. **MultiTenantAccessController** (`hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/MultiTenantAccessController.java:40`)
   - Interface for multi-tenant access control operations
   - Policy and role CRUD operations
   - Factory pattern for implementation selection

5. **RangerClientMultiTenantAccessController** (`hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:56`)
   - Implements MultiTenantAccessController using RangerClient
   - Manages Ranger policies and roles via REST API
   - Handles authentication (KERBEROS or SIMPLE)

6. **OMRangerBGSyncService** (`hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/service/OMRangerBGSyncService.java`)
   - Background synchronization service
   - Ensures eventual consistency between OM DB and Ranger
   - Leader-only execution in HA setups

---

## 2. Authorization Plugin System

### IAccessAuthorizer Interface

The core authorization contract in Ozone:

```java
public interface IAccessAuthorizer {
  boolean checkAccess(IOzoneObj ozoneObject, RequestContext context)
      throws OMException;

  enum ACLType {
    READ, WRITE, CREATE, LIST, DELETE,
    READ_ACL, WRITE_ACL, ALL, NONE
  }

  enum ACLIdentityType {
    USER, GROUP, WORLD, ANONYMOUS, CLIENT_IP
  }
}
```

**Key Design Decisions:**

1. **Single Responsibility**: One method (`checkAccess`) for all authorization decisions
2. **Context-based**: RequestContext encapsulates user, IP, ACL rights, and operation type
3. **Object-based**: IOzoneObj represents the resource (volume/bucket/key)
4. **Pluggable**: Different implementations can be swapped via configuration

### Authorization Flow

```
Client Request
     │
     ▼
┌────────────────────────────────────┐
│ OM Request Handler                 │
│  - Parse request                   │
│  - Extract user info (UGI)         │
│  - Build RequestContext            │
│  - Build IOzoneObj                 │
└────────────┬───────────────────────┘
             │
             ▼
┌────────────────────────────────────┐
│ IAccessAuthorizer.checkAccess()    │
│                                    │
│  Input:                            │
│  - IOzoneObj (resource)            │
│  - RequestContext (user, ACL type) │
│                                    │
│  Output: boolean (allow/deny)      │
└────────────┬───────────────────────┘
             │
      ┌──────┴──────┐
      │             │
      ▼             ▼
┌─────────┐   ┌──────────────┐
│ Ranger  │   │ Native Ozone │
│ Plugin  │   │ Authorizer   │
└─────────┘   └──────────────┘
```

### OzoneNativeAuthorizer Implementation

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/security/acl/OzoneNativeAuthorizer.java:87`

**Key Features:**

1. **Admin Bypass** (line 104):
   ```java
   if (adminCheck.test(context.getClientUgi())) {
     return true;
   }
   ```

2. **Read-Only Admin Bypass** (line 109):
   ```java
   if (readOnlyAdminCheck.test(context.getClientUgi())
       && (context.getAclRights() == ACLType.READ
       || context.getAclRights() == ACLType.READ_ACL
       || context.getAclRights() == ACLType.LIST)) {
     return true;
   }
   ```

3. **Owner Privileges** (line 116):
   ```java
   boolean isOwner = isOwner(context.getClientUgi(), context.getOwnerName());
   ```

4. **Hierarchical Checking** (lines 139-189):
   - **VOLUME**: Check volume ACLs
   - **BUCKET**: Check bucket ACLs → Check volume with parent ACL rights
   - **KEY**: Check key ACLs → Check prefix ACLs → Check bucket ACLs → Check volume ACLs
   - **PREFIX**: Check prefix ACLs → Check bucket ACLs → Check volume ACLs

**Parent ACL Rights Mapping:**
- CREATE on child → CREATE on parent
- DELETE on child → DELETE on parent
- Other operations → READ on parent

---

## 3. Ranger Client Integration

### RangerClientMultiTenantAccessController

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:73`

### Authentication Methods

**1. KERBEROS Authentication** (lines 108-127):
```java
if (fallbackUsername == null || fallbackPassword == null) {
  authType = AuthenticationMethod.KERBEROS.name();

  // Replace _HOST pattern with actual hostname
  omPrincipal = SecurityUtil.getServerPrincipal(
      configuredOmPrincipal, OmUtils.getOmAddress(conf).getHostName());

  String keytabPath = conf.get(OZONE_OM_KERBEROS_KEYTAB_FILE_KEY);

  // Short name for Ranger requests
  shortName = UserGroupInformation.createRemoteUser(omPrincipal)
      .getShortUserName();

  usernameOrPrincipal = omPrincipal;
  passwordOrKeytab = keytabPath;
}
```

**Configuration:**
- `ozone.om.kerberos.principal`: OM Kerberos principal (supports `_HOST` pattern)
- `ozone.om.kerberos.keytab.file`: Path to keytab file

**2. SIMPLE Authentication** (lines 98-106):
```java
if (fallbackUsername != null && fallbackPassword != null) {
  authType = AuthenticationMethod.SIMPLE.name();
  usernameOrPrincipal = fallbackUsername;
  passwordOrKeytab = fallbackPassword;
  omPrincipal = fallbackUsername;
  shortName = fallbackUsername;
}
```

**Configuration:**
- `ozone.om.ranger.https.admin.api.user`: Username for Ranger admin API
- `ozone.om.ranger.https.admin.api.passwd`: Password for Ranger admin API

### RangerClient Initialization

Lines 132-139:
```java
UserGroupInformation loginUser = UserGroupInformation.getLoginUser();
try {
  client = new RangerClient(rangerHttpsAddress,
      authType, usernameOrPrincipal, passwordOrKeytab,
      rangerServiceName, OzoneConsts.OZONE);
} finally {
  // Restore original login user
  UserGroupInformation.setLoginUser(loginUser);
}
```

**Important Notes:**
1. Authentication happens lazily - RangerClient doesn't authenticate during initialization
2. Invalid credentials result in 401 errors on subsequent requests
3. Login user is preserved and restored to avoid side effects

### Error Handling

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:155`

```java
private void decodeRSEStatusCodes(RangerServiceException rse) {
  ClientResponse.Status status = rse.getStatus();
  if (status == null) {
    LOG.error("Request failure with no status provided.", rse);
  } else {
    switch (status.getStatusCode()) {
    case HTTP_STATUS_CODE_UNAUTHORIZED: // 401
      LOG.error("Auth failure. Please double check Ranger-related configs");
      break;
    case HTTP_STATUS_CODE_BAD_REQUEST: // 400
      LOG.error("Request failure. If this is an assign-user operation, "
          + "check if the user name exists in Ranger.");
      break;
    default:
      LOG.error("Other request failure. Status: {}", status);
    }
  }
}
```

**Common Error Scenarios:**
- **401 Unauthorized**: Invalid Kerberos credentials or username/password
- **400 Bad Request**: User doesn't exist in Ranger, malformed policy
- **Other errors**: Network issues, Ranger service down

---

## 4. Policy Management Deep-Dive

### Policy Data Model

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/MultiTenantAccessController.java:341`

```java
class Policy {
  private final long id;              // Ranger policy ID
  private final String name;          // Policy name (must be unique)
  private final Set<String> volumes;  // Volume resources
  private final Set<String> buckets;  // Bucket resources
  private final Set<String> keys;     // Key resources
  private final String description;   // Policy description
  private final Map<String, Collection<Acl>> userAcls;  // User ACLs
  private final Map<String, Collection<Acl>> roleAcls;  // Role ACLs
  private final Set<String> labels;   // Policy labels (for sync)
  private final boolean isEnabled;    // Policy enabled/disabled
}
```

### ACL Structure

```java
class Acl {
  private final boolean isAllowed;    // true = allow, false = deny
  private final IAccessAuthorizer.ACLType acl;  // Permission type
}
```

**ACL Type Mapping to Ranger:**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/MultiTenantAccessController.java:80`

```java
static Map<IAccessAuthorizer.ACLType, String> getRangerAclStrings() {
  Map<IAccessAuthorizer.ACLType, String> rangerAclStrings = new EnumMap<>(IAccessAuthorizer.ACLType.class);
  rangerAclStrings.put(IAccessAuthorizer.ACLType.ALL, "all");
  rangerAclStrings.put(IAccessAuthorizer.ACLType.LIST, "list");
  rangerAclStrings.put(IAccessAuthorizer.ACLType.READ, "read");
  rangerAclStrings.put(IAccessAuthorizer.ACLType.WRITE, "write");
  rangerAclStrings.put(IAccessAuthorizer.ACLType.CREATE, "create");
  rangerAclStrings.put(IAccessAuthorizer.ACLType.DELETE, "delete");
  rangerAclStrings.put(IAccessAuthorizer.ACLType.READ_ACL, "read_acl");
  rangerAclStrings.put(IAccessAuthorizer.ACLType.WRITE_ACL, "write_acl");

  return rangerAclStrings;
}
```

### Policy Creation Flow

**1. Convert Ozone Policy to Ranger Policy**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:430`

```java
private RangerPolicy toRangerPolicy(Policy policy) {
  RangerPolicy rangerPolicy = new RangerPolicy();
  rangerPolicy.setName(policy.getName());
  rangerPolicy.setService(rangerServiceName);
  rangerPolicy.setPolicyLabels(new ArrayList<>(policy.getLabels()));

  // Add resources (volumes, buckets, keys)
  Map<String, RangerPolicy.RangerPolicyResource> resource = new HashMap<>();

  if (!policy.getVolumes().isEmpty()) {
    RangerPolicy.RangerPolicyResource volumeResources =
        new RangerPolicy.RangerPolicyResource();
    volumeResources.setValues(new ArrayList<>(policy.getVolumes()));
    resource.put("volume", volumeResources);
  }

  if (!policy.getBuckets().isEmpty()) {
    RangerPolicy.RangerPolicyResource bucketResources =
        new RangerPolicy.RangerPolicyResource();
    bucketResources.setValues(new ArrayList<>(policy.getBuckets()));
    resource.put("bucket", bucketResources);
  }

  if (!policy.getKeys().isEmpty()) {
    RangerPolicy.RangerPolicyResource keyResources =
        new RangerPolicy.RangerPolicyResource();
    keyResources.setValues(new ArrayList<>(policy.getKeys()));
    resource.put("key", keyResources);
  }

  rangerPolicy.setResources(resource);

  // Add user ACLs
  for (Map.Entry<String, Collection<Acl>> userAcls : policy.getUserAcls().entrySet()) {
    RangerPolicy.RangerPolicyItem item = new RangerPolicy.RangerPolicyItem();
    item.setUsers(Collections.singletonList(userAcls.getKey()));

    for (Acl acl : userAcls.getValue()) {
      RangerPolicy.RangerPolicyItemAccess access =
          new RangerPolicy.RangerPolicyItemAccess();
      access.setIsAllowed(acl.isAllowed());
      access.setType(aclToString.get(acl.getAclType()));
      item.getAccesses().add(access);
    }

    rangerPolicy.getPolicyItems().add(item);
  }

  // Add role ACLs (similar to user ACLs)
  for (Map.Entry<String, Collection<Acl>> roleAcls : policy.getRoleAcls().entrySet()) {
    RangerPolicy.RangerPolicyItem item = new RangerPolicy.RangerPolicyItem();
    item.setRoles(Collections.singletonList(roleAcls.getKey()));

    for (Acl acl : roleAcls.getValue()) {
      RangerPolicy.RangerPolicyItemAccess access =
          new RangerPolicy.RangerPolicyItemAccess();
      access.setIsAllowed(acl.isAllowed());
      access.setType(aclToString.get(acl.getAclType()));
      item.getAccesses().add(access);
    }

    rangerPolicy.getPolicyItems().add(item);
  }

  return rangerPolicy;
}
```

**2. Create Policy via RangerClient**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:175`

```java
@Override
public Policy createPolicy(Policy policy) throws IOException {
  if (LOG.isDebugEnabled()) {
    LOG.debug("Sending create request for policy {} to Ranger.", policy.getName());
  }
  RangerPolicy rangerPolicy;
  try {
    rangerPolicy = client.createPolicy(toRangerPolicy(policy));
  } catch (RangerServiceException e) {
    decodeRSEStatusCodes(e);
    throw new IOException(e);
  }
  return fromRangerPolicy(rangerPolicy);
}
```

### Policy Retrieval and Conversion

**Convert Ranger Policy to Ozone Policy**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:380`

```java
private Policy fromRangerPolicy(RangerPolicy rangerPolicy) {
  Policy.Builder policyBuilder = new Policy.Builder();

  // Extract roles and their ACLs
  for (RangerPolicy.RangerPolicyItem policyItem : rangerPolicy.getPolicyItems()) {
    Collection<Acl> acls = new ArrayList<>();

    for (RangerPolicy.RangerPolicyItemAccess access : policyItem.getAccesses()) {
      if (access.getIsAllowed()) {
        acls.add(Acl.allow(stringToAcl.get(access.getType())));
      } else {
        acls.add(Acl.deny(stringToAcl.get(access.getType())));
      }
    }

    for (String roleName : policyItem.getRoles()) {
      policyBuilder.addRoleAcl(roleName, acls);
    }
  }

  // Extract resources
  for (Map.Entry<String, RangerPolicy.RangerPolicyResource> resource :
       rangerPolicy.getResources().entrySet()) {
    String resourceType = resource.getKey();
    List<String> resourceNames = resource.getValue().getValues();

    switch (resourceType) {
    case "volume":
      policyBuilder.addVolumes(resourceNames);
      break;
    case "bucket":
      policyBuilder.addBuckets(resourceNames);
      break;
    case "key":
      policyBuilder.addKeys(resourceNames);
      break;
    default:
      LOG.warn("Pulled Ranger policy with unknown resource type '{}' with names '{}'",
          resourceType, String.join(",", resourceNames));
    }
  }

  policyBuilder.setName(rangerPolicy.getName())
      .setId(rangerPolicy.getId())
      .setDescription(rangerPolicy.getDescription())
      .addLabels(rangerPolicy.getPolicyLabels());

  return policyBuilder.build();
}
```

### Policy Labels

**Purpose**: Labels are used to identify Ozone-managed policies for synchronization.

**Usage in Multi-Tenancy:**

From documentation: `hadoop-hdds/docs/content/feature/S3-Multi-Tenancy-Access-Control.md`

- `om-tenant-{tenantName}`: Identifies policies belonging to a specific tenant
- Background sync service uses labels to filter Ozone-managed policies
- Prevents accidental modification of non-Ozone Ranger policies

**Getting Labeled Policies:**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:207`

```java
@Override
public List<Policy> getLabeledPolicies(String label) throws IOException {
  if (LOG.isDebugEnabled()) {
    LOG.debug("Sending get request for policies with label {} to Ranger.", label);
  }
  Map<String, String> filterMap = new HashMap<>();
  filterMap.put("serviceName", rangerServiceName);
  filterMap.put("policyLabelsPartial", label);

  try {
    return client.findPolicies(filterMap).stream()
        .map(this::fromRangerPolicy)
        .collect(Collectors.toList());
  } catch (RangerServiceException e) {
    decodeRSEStatusCodes(e);
    throw new IOException(e);
  }
}
```

---

## 5. Role Management Deep-Dive

### Role Data Model

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/MultiTenantAccessController.java:152`

```java
class Role {
  private final String name;                      // Role name
  private final Map<String, Boolean> usersMap;    // userName -> isRoleAdmin
  private final Map<String, Boolean> rolesMap;    // roleName -> isRoleAdmin
  private final String description;               // Role description
  private final Long id;                          // Ranger role ID
  private final String createdByUser;             // Creator username
}
```

**Key Concepts:**

1. **Role Members**: Users assigned to the role
2. **Role Admins**: Members who can manage the role (add/remove users)
3. **Nested Roles**: Roles can contain other roles
4. **Created By User**: Required by Ranger for audit and ownership tracking

### Role Creation

**1. Convert Ozone Role to Ranger Role**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:364`

```java
private static RangerRole toRangerRole(Role role, String createdByUser) {
  RangerRole rangerRole = new RangerRole();
  rangerRole.setName(role.getName());
  rangerRole.setCreatedByUser(createdByUser);

  if (!role.getUsersMap().isEmpty()) {
    rangerRole.setUsers(toRangerRoleMembers(role.getUsersMap()));
  }

  if (!role.getRolesMap().isEmpty()) {
    rangerRole.setRoles(toRangerRoleMembers(role.getRolesMap()));
  }

  if (role.getDescription().isPresent()) {
    rangerRole.setDescription(role.getDescription().get());
  }

  return rangerRole;
}

private static List<RangerRole.RoleMember> toRangerRoleMembers(
    Map<String, Boolean> members) {
  return members.entrySet().stream()
      .map(entry -> {
        final String princ = entry.getKey();
        final boolean isRoleAdmin = entry.getValue();
        return new RangerRole.RoleMember(princ, isRoleAdmin);
      })
      .collect(Collectors.toList());
}
```

**2. Create Role via RangerClient**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:257`

```java
@Override
public Role createRole(Role role) throws IOException {
  if (LOG.isDebugEnabled()) {
    LOG.debug("Sending create request for role {} to Ranger.", role.getName());
  }
  final RangerRole rangerRole;
  try {
    rangerRole = client.createRole(rangerServiceName,
        toRangerRole(role, shortName));  // shortName is the OM principal
  } catch (RangerServiceException e) {
    decodeRSEStatusCodes(e);
    throw new IOException(e);
  }
  return fromRangerRole(rangerRole);
}
```

### Role Retrieval and Conversion

**Convert Ranger Role to Ozone Role**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:354`

```java
private static Role fromRangerRole(RangerRole rangerRole) {
  return new Role.Builder()
    .setID(rangerRole.getId())
    .setName(rangerRole.getName())
    .setDescription(rangerRole.getDescription())
    .addUsers(fromRangerRoleMembers(rangerRole.getUsers()))
    .setCreatedByUser(rangerRole.getCreatedByUser())
    .build();
}

private static List<String> fromRangerRoleMembers(
    Collection<RangerRole.RoleMember> members) {
  return members.stream()
      .map(rangerUser -> rangerUser.getName())
      .collect(Collectors.toList());
}
```

**Get Role by Name:**

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:274`

```java
@Override
public Role getRole(String roleName) throws IOException {
  if (LOG.isDebugEnabled()) {
    LOG.debug("Sending get request for role {} to Ranger.", roleName);
  }
  final RangerRole rangerRole;
  try {
    rangerRole = client.getRole(roleName, shortName, rangerServiceName);
  } catch (RangerServiceException e) {
    decodeRSEStatusCodes(e);
    throw new IOException(e);
  }
  return fromRangerRole(rangerRole);
}
```

### Role Update

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:290`

```java
@Override
public Role updateRole(long roleId, Role role) throws IOException {
  if (LOG.isDebugEnabled()) {
    LOG.debug("Sending update request for role ID {} to Ranger.", roleId);
  }
  final RangerRole rangerRole;
  try {
    rangerRole = client.updateRole(roleId, toRangerRole(role, shortName));
  } catch (RangerServiceException e) {
    decodeRSEStatusCodes(e);
    throw new IOException(e);
  }
  return fromRangerRole(rangerRole);
}
```

**Important Notes:**
1. Role update requires the role ID (obtained from getRole)
2. createdByUser is set to shortName (OM principal)
3. Update replaces the entire role definition

### Role Deletion

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/RangerClientMultiTenantAccessController.java:308`

```java
@Override
public void deleteRole(String roleName) throws IOException {
  if (LOG.isDebugEnabled()) {
    LOG.debug("Sending delete request for role {} to Ranger.", roleName);
  }
  try {
    client.deleteRole(roleName, shortName, rangerServiceName);
  } catch (RangerServiceException e) {
    decodeRSEStatusCodes(e);
    throw new IOException(e);
  }
}
```

---

## 6. Background Synchronization Mechanism

### OMRangerBGSyncService

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/service/OMRangerBGSyncService.java`

**Purpose**: Ensures eventual consistency between Ozone Manager DB and Ranger by:
- Creating missing policies/roles in Ranger
- Deleting orphaned policies/roles from Ranger
- Updating role memberships based on OM DB state

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│            OM Ratis Leader (Active)                     │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │        OMRangerBGSyncService                      │  │
│  │  (Runs periodically, leader-only)                │  │
│  └──────────────┬───────────────────────────────────┘  │
│                 │                                        │
│                 │ Every 600s (configurable)             │
│                 ▼                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │  1. Load all policies/roles from Ranger          │  │
│  │     - Filter by policy labels (om-tenant-*)      │  │
│  │     - Get policy version from Ranger             │  │
│  └──────────────┬───────────────────────────────────┘  │
│                 │                                        │
│                 ▼                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │  2. Load tenant state from OM DB                 │  │
│  │     - Use optimistic read locking                │  │
│  │     - Get all tenants and user assignments       │  │
│  └──────────────┬───────────────────────────────────┘  │
│                 │                                        │
│                 ▼                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │  3. Compare and reconcile                        │  │
│  │     - Create missing policies in Ranger          │  │
│  │     - Delete orphaned policies from Ranger       │  │
│  │     - Create missing roles in Ranger             │  │
│  │     - Update role memberships                    │  │
│  │     - Delete orphaned roles from Ranger          │  │
│  └──────────────┬───────────────────────────────────┘  │
│                 │                                        │
│                 ▼                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │  4. Persist sync state to OM DB                  │  │
│  │     - Update policy version in OM DB             │  │
│  │     - Only if sync was successful                │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Configuration

From `hadoop-ozone/common/src/main/java/org/apache/hadoop/ozone/om/OMConfigKeys.java`:

```java
// Sync interval - how often to run sync
public static final String OZONE_OM_MULTITENANCY_RANGER_SYNC_INTERVAL
    = "ozone.om.multitenancy.ranger.sync.interval";
public static final TimeDuration
    OZONE_OM_MULTITENANCY_RANGER_SYNC_INTERVAL_DEFAULT
    = TimeDuration.valueOf(600, TimeUnit.SECONDS);  // 10 minutes

// Sync timeout - max time for one sync operation
public static final String OZONE_OM_MULTITENANCY_RANGER_SYNC_TIMEOUT
    = "ozone.om.multitenancy.ranger.sync.timeout";
public static final TimeDuration
    OZONE_OM_MULTITENANCY_RANGER_SYNC_TIMEOUT_DEFAULT
    = TimeDuration.valueOf(10, TimeUnit.SECONDS);
```

### Key Synchronization Logic

Based on analysis of `OMRangerBGSyncService.java`:

**1. Version-Based Sync Detection**

The service tracks Ranger policy version to detect changes:

```java
// Get current policy version from Ranger
long getRangerServicePolicyVersion() throws IOException {
  RangerService rangerOzoneService = client.getService(rangerServiceName);
  final Long policyVersion = rangerOzoneService.getPolicyVersion();
  return policyVersion == null ? -1L : policyVersion;
}
```

**Policy Version Tracking:**
- Stored in OM DB as `OmRangerSyncArgs`
- Compared with Ranger's current version each sync run
- If versions differ, policies have been modified externally

**2. Leader-Only Execution**

- Only the OM Ratis leader executes the sync
- Followers skip sync to avoid conflicts
- Ensures exactly-once execution in HA setups

**3. Optimistic Read Locking**

- OM DB reads use optimistic locking to avoid write conflicts
- If concurrent writes occur, sync retries (max 2 attempts)
- Prevents synchronization from blocking tenant operations

**4. Policy Synchronization**

```
For each tenant in OM DB:
  1. Check if corresponding Ranger policy exists
  2. If missing:
     - Create policy with label "om-tenant-{tenantName}"
     - Add default UserRole and AdminRole to policy
  3. If exists:
     - Verify policy resources match (volume, bucket)
     - Update if necessary

For each Ranger policy with Ozone labels:
  1. Check if corresponding tenant exists in OM DB
  2. If missing:
     - Delete orphaned policy from Ranger
     - Log warning about cleanup
```

**5. Role Synchronization**

```
For each tenant in OM DB:
  1. Get expected roles: {tenant}-UserRole, {tenant}-AdminRole
  2. For each role:
     a. Check if role exists in Ranger
     b. If missing:
        - Create role in Ranger
        - Set createdByUser to OM principal
     c. If exists:
        - Get current members from Ranger
        - Get expected members from OM DB
        - Calculate diff (add/remove operations)
        - Update role membership via updateRole()

For each Ranger role for Ozone tenants:
  1. Check if corresponding tenant exists in OM DB
  2. If missing:
     - Delete orphaned role from Ranger
     - Log warning about cleanup
```

**6. Error Handling and Retry**

- Each sync operation has a timeout (default 10s)
- If timeout occurs, sync is aborted and retried next cycle
- Maximum 2 attempts per sync run
- Errors are logged but don't crash the service
- Invalid credentials result in 401 errors logged continuously

### Sync State Persistence

**OmRangerSyncArgs Data Class**

Location: `hadoop-ozone/common/src/main/java/org/apache/hadoop/ozone/om/helpers/OmRangerSyncArgs.java`

```java
@Data
@Builder
public class OmRangerSyncArgs {
  private long rangerServicePolicyVersion;
  private Instant lastSyncTime;
  private boolean syncSuccessful;

  // Serialization methods for OM DB storage
  public byte[] toProtobuf() { ... }
  public static OmRangerSyncArgs fromProtobuf(byte[] data) { ... }
}
```

**Persistence Flow:**

1. After successful sync:
   ```java
   OmRangerSyncArgs syncArgs = OmRangerSyncArgs.builder()
       .rangerServicePolicyVersion(currentPolicyVersion)
       .lastSyncTime(Instant.now())
       .syncSuccessful(true)
       .build();

   // Store in OM DB
   omMetadataManager.getRangerSyncTable().put(SYNC_KEY, syncArgs);
   ```

2. On next sync run:
   ```java
   OmRangerSyncArgs lastSync = omMetadataManager.getRangerSyncTable().get(SYNC_KEY);

   if (lastSync != null &&
       lastSync.getRangerServicePolicyVersion() == currentPolicyVersion) {
     // No changes in Ranger, skip detailed sync
     LOG.debug("Ranger policy version unchanged, skipping sync");
     return;
   }
   ```

### Sync Observability

**Metrics and Logging:**

1. **Sync Execution Metrics:**
   - Sync run count
   - Sync success/failure count
   - Sync duration
   - Policies created/deleted/updated
   - Roles created/deleted/updated

2. **Log Messages:**
   - `INFO`: Sync started/completed, version changes
   - `DEBUG`: Detailed policy/role operations
   - `WARN`: Orphaned resources cleanup
   - `ERROR`: Sync failures, authentication issues, timeouts

---

## 7. Multi-Tenancy Integration

### Multi-Tenancy Concepts

From documentation: `hadoop-hdds/docs/content/feature/S3-Multi-Tenancy.md`

**Key Concepts:**

1. **Tenant**: A logical namespace for S3 users within a volume
2. **Tenant Volume**: Each tenant maps to an Ozone volume
3. **S3 Access**: Users get S3-compatible access via Access ID and Secret Key
4. **Ranger-Backed Authorization**: All tenant access is controlled via Ranger policies

### Tenant Creation Flow

```
1. Admin creates tenant:
   ozone tenant create <tenant-name>

2. OM creates tenant structures:
   a. Create volume: /volume/<tenant-name>
   b. Create Ranger policy: om-tenant-<tenant-name>
      - Resource: volume=<tenant-name>, bucket=*, key=*
      - Initially empty (no users)
   c. Create Ranger roles:
      - <tenant-name>-UserRole: Regular user access
      - <tenant-name>-AdminRole: Admin access
   d. Add roles to policy:
      - UserRole: READ, WRITE, LIST on volume/bucket/key
      - AdminRole: ALL permissions on volume/bucket/key

3. OM stores tenant metadata in DB:
   - Tenant name
   - Volume name
   - Role IDs
   - Policy ID
```

### Default Tenant Policies

From documentation: `hadoop-hdds/docs/content/feature/S3-Multi-Tenancy-Access-Control.md`

**UserRole Permissions:**
- `READ`: Read objects
- `WRITE`: Write objects
- `LIST`: List buckets and objects
- `DELETE`: Delete objects (optional, can be removed)

**AdminRole Permissions:**
- `ALL`: Complete control over tenant resources
- Can create/delete buckets
- Can manage bucket policies
- Can assign/revoke user access

**Policy Structure:**

```yaml
Policy Name: om-tenant-<tenant-name>
Labels: [om-tenant-<tenant-name>]
Resources:
  volume: <tenant-name>
  bucket: *
  key: *

Policy Items:
  - Role: <tenant-name>-UserRole
    Permissions: [READ, WRITE, LIST, DELETE]

  - Role: <tenant-name>-AdminRole
    Permissions: [ALL]
```

### User Assignment Flow

```
1. Admin assigns user to tenant:
   ozone tenant user assign <user-name> --tenant=<tenant-name>

2. OM assigns user to tenant:
   a. Look up tenant in OM DB
   b. Get UserRole for tenant: <tenant-name>-UserRole
   c. Add user to UserRole in OM DB
   d. Background sync detects change:
      - Compares OM DB role members with Ranger role members
      - Calls updateRole() to add user to Ranger
   e. Generate S3 Access ID and Secret Key:
      - Access ID: <tenant-name>$<user-name>
      - Secret Key: Random generated, stored encrypted
   f. Return credentials to user

3. User accesses S3:
   - Authenticates with Access ID and Secret Key
   - S3 Gateway translates to Ozone operations
   - RangerOzoneAuthorizer checks permissions:
     - User is member of <tenant-name>-UserRole
     - UserRole has permissions on tenant volume
   - Access granted/denied based on policy
```

### Multi-Tenant Access Controller Factory

Location: `hadoop-ozone/ozone-manager/src/main/java/org/apache/hadoop/ozone/om/multitenant/MultiTenantAccessController.java:549`

```java
static MultiTenantAccessController create(ConfigurationSource conf) {
  if (conf.getBoolean(OZONE_OM_TENANT_DEV_SKIP_RANGER, false)) {
    // Development mode: use in-memory implementation
    return new InMemoryMultiTenantAccessController();
  }

  // Production mode: use Ranger client implementation
  final String className =
      "org.apache.hadoop.ozone.om.multitenant.RangerClientMultiTenantAccessController";
  return ReflectionUtils.newInstance(
      ReflectionUtils.getClass(className, MultiTenantAccessController.class),
      new Class<?>[] {ConfigurationSource.class},
      conf
  );
}
```

**Development vs Production:**

1. **Development Mode** (`ozone.om.tenant.dev.skip.ranger=true`):
   - Uses `InMemoryMultiTenantAccessController`
   - No actual Ranger communication
   - Policies/roles stored in memory only
   - Useful for testing without Ranger deployment

2. **Production Mode** (default):
   - Uses `RangerClientMultiTenantAccessController`
   - Communicates with Ranger via REST API
   - Persistent policy storage in Ranger
   - Full audit logging

---

## 8. Configuration Guide

### Required Configurations

**1. Enable ACLs:**
```xml
<property>
  <name>ozone.acl.enabled</name>
  <value>true</value>
</property>
```

**2. Set Authorizer Implementation:**

**For Ranger Authorization:**
```xml
<property>
  <name>ozone.acl.authorizer.class</name>
  <value>org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer</value>
</property>
```

**For Native Authorization:**
```xml
<property>
  <name>ozone.acl.authorizer.class</name>
  <value>org.apache.hadoop.ozone.security.acl.OzoneNativeAuthorizer</value>
</property>
```

### Ranger Connection Configuration

**1. Ranger Service Configuration:**

```xml
<property>
  <name>ozone.om.ranger.https-address</name>
  <value>https://ranger-server:6182</value>
  <description>Ranger Admin server HTTPS address</description>
</property>

<property>
  <name>ozone.om.ranger.service</name>
  <value>cm_ozone</value>
  <description>Ranger service name for Ozone</description>
</property>
```

**2. Authentication Configuration:**

**Option A: Kerberos Authentication (Recommended for Production):**

```xml
<property>
  <name>ozone.om.kerberos.principal</name>
  <value>om/_HOST@REALM</value>
  <description>OM Kerberos principal (_HOST will be replaced with hostname)</description>
</property>

<property>
  <name>ozone.om.kerberos.keytab.file</name>
  <value>/etc/security/keytabs/om.keytab</value>
  <description>Path to OM keytab file</description>
</property>
```

**Option B: Simple Authentication (Development Only):**

```xml
<property>
  <name>ozone.om.ranger.https.admin.api.user</name>
  <value>admin</value>
  <description>Ranger admin username</description>
</property>

<property>
  <name>ozone.om.ranger.https.admin.api.passwd</name>
  <value>admin_password</value>
  <description>Ranger admin password (NOT SECURE - use only for testing)</description>
</property>
```

### Multi-Tenancy Configuration

**1. Enable Multi-Tenancy:**

```xml
<property>
  <name>ozone.om.multitenancy.enabled</name>
  <value>true</value>
</property>
```

**2. Ranger Sync Configuration:**

```xml
<property>
  <name>ozone.om.multitenancy.ranger.sync.interval</name>
  <value>600s</value>
  <description>How often to sync with Ranger (default: 10 minutes)</description>
</property>

<property>
  <name>ozone.om.multitenancy.ranger.sync.timeout</name>
  <value>10s</value>
  <description>Timeout for each sync operation (default: 10 seconds)</description>
</property>
```

### Optional Ranger Configuration

**1. Connection Timeouts:**

```xml
<property>
  <name>ozone.om.ranger.connection.timeout</name>
  <value>5s</value>
  <description>Ranger HTTP connection timeout</description>
</property>

<property>
  <name>ozone.om.ranger.connection.request.timeout</name>
  <value>5s</value>
  <description>Ranger HTTP request timeout</description>
</property>
```

**2. SSL Certificate Validation (Development Only):**

```xml
<property>
  <name>ozone.om.ranger.ignore.cert</name>
  <value>false</value>
  <description>
    Ignore SSL certificate validation (INSECURE - use only for testing)
    Default: true (TODO: should be false in production)
  </description>
</property>
```

**3. Development Mode:**

```xml
<property>
  <name>ozone.om.tenant.dev.skip.ranger</name>
  <value>false</value>
  <description>
    Skip Ranger and use in-memory access controller (development only)
    Default: false
  </description>
</property>
```

### OM High Availability Configuration

When running OM in HA mode, Ranger sync runs only on the leader:

```xml
<property>
  <name>ozone.om.service.ids</name>
  <value>omservice</value>
</property>

<property>
  <name>ozone.om.nodes.omservice</name>
  <value>om1,om2,om3</value>
</property>

<!-- OM addresses for each node -->
<property>
  <name>ozone.om.address.omservice.om1</name>
  <value>om1-host:9862</value>
</property>
<!-- ... similar for om2, om3 -->
```

The leader OM will:
- Execute background sync with Ranger
- Persist sync state to Ratis-replicated OM DB
- Follower OMs will replicate the sync state but won't execute sync themselves

---

## 9. Code Flow Examples

### Example 1: Key Write Authorization Flow

**Scenario**: User `alice` wants to write key `/vol1/bucket1/data.txt`

```
1. S3 Gateway receives PutObject request:
   - Access ID: tenant1$alice
   - Bucket: bucket1
   - Key: data.txt

2. S3 Gateway validates credentials and translates to Ozone:
   - Extract tenant: tenant1
   - Extract user: alice
   - Map to volume: vol1 (tenant1's volume)

3. OM receives CreateKey request:
   OzoneManagerProtocolClientSideTranslatorPB.createKey(
     volumeName="vol1",
     bucketName="bucket1",
     keyArgs=KeyArgs(keyName="data.txt", ...)
   )

4. OM builds authorization context:
   UserGroupInformation ugi = UserGroupInformation.createRemoteUser("alice");

   IOzoneObj ozoneObj = OzoneObjInfo.Builder()
       .setVolumeName("vol1")
       .setBucketName("bucket1")
       .setKeyName("data.txt")
       .setResType(OzoneObj.ResourceType.KEY)
       .setStoreType(OzoneObj.StoreType.OZONE)
       .build();

   RequestContext context = RequestContext.Builder()
       .setClientUgi(ugi)
       .setAclRights(ACLType.WRITE)
       .setAclType(IAccessAuthorizer.ACLIdentityType.USER)
       .build();

5. OM calls authorizer:
   boolean authorized = accessAuthorizer.checkAccess(ozoneObj, context);

6. RangerOzoneAuthorizer evaluates:
   - Contacts Ranger plugin (local cache or remote call)
   - Ranger evaluates policies:
     a. Find policies for volume=vol1, bucket=bucket1, key=data.txt
     b. Check if user alice has WRITE permission:
        - alice is member of tenant1-UserRole
        - tenant1-UserRole is in policy om-tenant-tenant1
        - Policy grants WRITE on vol1/bucket1/*
     c. Return ALLOW

7. OM proceeds with key creation:
   - Allocates blocks from SCM
   - Creates key entry in OM DB
   - Returns key info to client

8. If authorization failed (step 6 returns DENY):
   - OM throws OMException with PERMISSION_DENIED
   - Client receives 403 Forbidden
```

### Example 2: Tenant User Assignment Flow

**Scenario**: Admin assigns user `bob` to `tenant1`

```
1. Admin executes command:
   ozone tenant user assign bob --tenant=tenant1

2. CLI calls OM.tenantAssignUserAccessId():
   TenantAssignUserAccessIdRequest request = TenantAssignUserAccessIdRequest.Builder()
       .setUsername("bob")
       .setTenantId("tenant1")
       .setAccessId("tenant1$bob")
       .build();

   TenantAssignUserAccessIdResponse response =
       om.tenantAssignUserAccessId(request);

3. OM processes request (OMTenantAssignUserAccessIdRequest handler):
   a. Validate request:
      - Check if tenant exists in OM DB
      - Check if user already assigned

   b. Get tenant info from OM DB:
      OmDBTenantState tenantState =
          omMetadataManager.getTenantStateTable().get("tenant1");

   c. Get UserRole for tenant:
      String userRoleName = tenantState.getTenantRoleName(); // "tenant1-UserRole"

   d. Update OM DB (transactional):
      - Add user to tenant's UserRole in tenantRoleTable
      - Create S3 secret for user (encrypted)
      - Store in tenantAccessIdTable

   e. Prepare response:
      - Access ID: tenant1$bob
      - Secret: <generated-secret>

4. Background sync detects change (next sync run):
   a. Load tenant state from OM DB:
      - tenant1-UserRole should have members: [alice, bob]

   b. Load role from Ranger:
      Role rangerRole = accessController.getRole("tenant1-UserRole");
      - Current members in Ranger: [alice]

   c. Calculate diff:
      - Users to add: [bob]
      - Users to remove: []

   d. Update role in Ranger:
      Role updatedRole = Role.Builder(rangerRole)
          .addUser("bob", false)  // false = not role admin
          .build();

      accessController.updateRole(rangerRole.getId(), updatedRole);

   e. Persist sync state:
      - Update policy version in OM DB
      - Mark sync successful

5. User bob can now access tenant1 resources:
   - Authenticates with Access ID: tenant1$bob, Secret: <secret>
   - Ranger evaluates access:
     - bob is member of tenant1-UserRole
     - tenant1-UserRole has WRITE permission on tenant1/*
   - Access granted
```

### Example 3: Background Sync Reconciliation

**Scenario**: Admin manually deletes a role in Ranger UI

```
Initial State:
  OM DB:
    - Tenant: tenant1
    - UserRole: tenant1-UserRole (members: alice, bob)
    - AdminRole: tenant1-AdminRole (members: admin1)

  Ranger:
    - Role: tenant1-UserRole (members: alice, bob)
    - Role: tenant1-AdminRole (members: admin1)
    - Policy: om-tenant-tenant1 (roles: tenant1-UserRole, tenant1-AdminRole)

Admin Action:
  - Deletes tenant1-UserRole in Ranger UI (manual operation)

Ranger State After Deletion:
  - Role: tenant1-AdminRole (members: admin1)
  - Policy: om-tenant-tenant1 (roles: tenant1-AdminRole)
    ⚠️ Policy now broken - references deleted role!

Background Sync Execution (next cycle):

1. Load state from Ranger:
   List<Policy> rangerPolicies = accessController.getLabeledPolicies("om-tenant-tenant1");
   // Returns: [om-tenant-tenant1 policy]

   Map<String, Role> rangerRoles = new HashMap<>();
   for (Policy policy : rangerPolicies) {
     for (String roleName : policy.getRoleAcls().keySet()) {
       try {
         Role role = accessController.getRole(roleName);
         rangerRoles.put(roleName, role);
       } catch (NotFoundException e) {
         LOG.warn("Policy references missing role: {}", roleName);
       }
     }
   }
   // Result: {tenant1-AdminRole: <role>}
   // Missing: tenant1-UserRole (404 Not Found)

2. Load state from OM DB:
   OmDBTenantState tenantState = omMetadataManager.getTenantStateTable().get("tenant1");
   String userRoleName = tenantState.getTenantRoleName(); // "tenant1-UserRole"
   String adminRoleName = tenantState.getTenantAdminRoleName(); // "tenant1-AdminRole"

   List<String> expectedRoles = [userRoleName, adminRoleName];

3. Detect missing roles:
   for (String expectedRole : expectedRoles) {
     if (!rangerRoles.containsKey(expectedRole)) {
       LOG.warn("Role {} missing in Ranger, recreating", expectedRole);

       // Recreate role from OM DB state
       OmDBUserRoleInfo roleInfo = omMetadataManager.getTenantRoleTable()
           .get(expectedRole);

       Role role = Role.Builder()
           .setName(expectedRole)
           .addUsers(roleInfo.getMembers())  // [alice, bob]
           .setDescription("Auto-recreated by OMRangerBGSyncService")
           .build();

       Role createdRole = accessController.createRole(role);
       LOG.info("Recreated role {} with ID {}", expectedRole, createdRole.getId());
     }
   }

4. Verify policy consistency:
   Policy policy = rangerPolicies.get(0);
   Set<String> policyRoles = policy.getRoleAcls().keySet();

   if (!policyRoles.containsAll(expectedRoles)) {
     LOG.warn("Policy missing expected roles, updating");

     Policy.Builder policyBuilder = new Policy.Builder()
         .setId(policy.getId())
         .setName(policy.getName())
         .addVolumes(policy.getVolumes())
         .addBuckets(policy.getBuckets())
         .addKeys(policy.getKeys())
         .addLabels(policy.getLabels());

     // Re-add all expected roles with correct permissions
     for (String roleName : expectedRoles) {
       Collection<Acl> acls;
       if (roleName.endsWith("-AdminRole")) {
         acls = [Acl.allow(ACLType.ALL)];
       } else {
         acls = [
           Acl.allow(ACLType.READ),
           Acl.allow(ACLType.WRITE),
           Acl.allow(ACLType.LIST),
           Acl.allow(ACLType.DELETE)
         ];
       }
       policyBuilder.addRoleAcl(roleName, acls);
     }

     Policy updatedPolicy = policyBuilder.build();
     accessController.updatePolicy(updatedPolicy);
     LOG.info("Updated policy {} to include all expected roles", policy.getName());
   }

5. Persist sync state:
   long currentPolicyVersion = accessController.getRangerServicePolicyVersion();

   OmRangerSyncArgs syncArgs = OmRangerSyncArgs.builder()
       .rangerServicePolicyVersion(currentPolicyVersion)
       .lastSyncTime(Instant.now())
       .syncSuccessful(true)
       .build();

   omMetadataManager.getRangerSyncTable().put(SYNC_KEY, syncArgs);
   LOG.info("Sync completed successfully, policy version: {}", currentPolicyVersion);

Result:
  - tenant1-UserRole recreated in Ranger with members [alice, bob]
  - Policy om-tenant-tenant1 updated to include both roles
  - Users alice and bob regain access to tenant1 resources
  - Audit log shows role deletion and recreation
```

---

## 10. Deployment Considerations

### Pre-Deployment Checklist

**1. Ranger Setup:**
- [ ] Ranger Admin server installed and running
- [ ] Ranger Ozone plugin installed on all OM nodes
- [ ] Ranger service definition created for Ozone (service name: e.g., `cm_ozone`)
- [ ] SSL/TLS certificates configured (or disabled for testing)
- [ ] Ranger database (MySQL/Postgres) properly configured

**2. Authentication Setup:**
- [ ] KDC configured if using Kerberos
- [ ] OM principals created: `om/_HOST@REALM`
- [ ] Keytabs distributed to all OM nodes
- [ ] Ranger principals created if using Ranger SPNEGO auth
- [ ] Test Ranger authentication: `kinit -kt <keytab> <principal>` then access Ranger UI

**3. Ozone Configuration:**
- [ ] `ozone.acl.enabled=true` in ozone-site.xml
- [ ] `ozone.acl.authorizer.class` set to RangerOzoneAuthorizer
- [ ] Ranger connection configs (`ozone.om.ranger.https-address`, etc.)
- [ ] Multi-tenancy enabled if needed (`ozone.om.multitenancy.enabled=true`)
- [ ] Sync intervals configured appropriately for your environment

**4. Network and Firewall:**
- [ ] OM can reach Ranger Admin HTTPS port (default: 6182)
- [ ] Firewall rules allow OM → Ranger communication
- [ ] DNS resolution working for Ranger hostname
- [ ] Test connectivity: `curl -k https://ranger-server:6182`

### Docker Compose Example

From: `hadoop-ozone/dist/src/main/compose/ozonesecure-ha/ranger.yaml`

```yaml
services:
  ranger:
    image: apache/ranger:2.1.0
    ports:
      - 6080:6080    # Ranger UI (HTTP)
      - 6182:6182    # Ranger Admin API (HTTPS)
    environment:
      RANGER_DB_HOST: ranger-db
      RANGER_DB_PASSWORD: rangerpassword
    volumes:
      - ./ranger-plugins:/opt/ranger/plugins
    depends_on:
      - ranger-db

  om:
    image: apache/ozone:latest
    environment:
      # Ranger connection
      OZONE_OPTS: >
        -Dozone.acl.enabled=true
        -Dozone.acl.authorizer.class=org.apache.ranger.authorization.ozone.authorizer.RangerOzoneAuthorizer
        -Dozone.om.ranger.https-address=https://ranger:6182
        -Dozone.om.ranger.service=cm_ozone

      # Kerberos authentication
      OZONE_OM_KERBEROS_PRINCIPAL: om/om@EXAMPLE.COM
      OZONE_OM_KERBEROS_KEYTAB_FILE: /etc/security/keytabs/om.keytab

      # Multi-tenancy
      OZONE_OM_MULTITENANCY_ENABLED: "true"
      OZONE_OM_MULTITENANCY_RANGER_SYNC_INTERVAL: 300s
    volumes:
      - ./keytabs:/etc/security/keytabs
      - ./ranger-plugins:/opt/hadoop/share/ozone/plugins
    command: >
      bash -c "
        # Install Ranger plugin
        /opt/hadoop/ranger-plugin-install.sh &&

        # Start OM
        ozone om
      "
```

### Monitoring and Observability

**1. OM Logs:**

Monitor these log patterns:
```bash
# Authentication failures
grep "Auth failure" /var/log/ozone/om.log

# Sync operations
grep "OMRangerBGSyncService" /var/log/ozone/om.log

# Policy operations
grep "Sending.*request for policy" /var/log/ozone/om.log

# Role operations
grep "Sending.*request for role" /var/log/ozone/om.log
```

**2. Ranger Audit Logs:**

Ranger audit DB/Solr shows:
- All access decisions (ALLOW/DENY)
- User, resource, permission, result
- Timestamp and policy applied
- Client IP address

Query example:
```sql
SELECT event_time, user, resource, access_type, policy, result
FROM ranger_audit_logs
WHERE service_name = 'cm_ozone'
  AND event_time > NOW() - INTERVAL 1 HOUR
ORDER BY event_time DESC;
```

**3. Metrics:**

Key metrics to monitor:
- `om_ranger_sync_run_count`: Total sync runs
- `om_ranger_sync_success_count`: Successful syncs
- `om_ranger_sync_failure_count`: Failed syncs
- `om_ranger_sync_duration_ms`: Sync duration
- `om_ranger_policy_create_count`: Policies created
- `om_ranger_role_update_count`: Roles updated

**4. Health Checks:**

```bash
# Check OM Ranger connectivity
curl -k -u admin:password https://ranger-server:6182/service/public/api/service/name/cm_ozone

# Check policy version
curl -k -u admin:password https://ranger-server:6182/service/public/v2/api/service/cm_ozone | jq .policyVersion

# Check OM leader (in HA setup)
ozone admin om getserviceroles --service-id=omservice
```

### Troubleshooting Guide

**Problem 1: Authentication Failures (401)**

Symptoms:
```
ERROR RangerClientMultiTenantAccessController - Auth failure. Please double check Ranger-related configs
```

Solutions:
1. Verify Kerberos ticket:
   ```bash
   kinit -kt /etc/security/keytabs/om.keytab om/$(hostname)@REALM
   klist  # Should show valid ticket
   ```

2. Check Ranger service definition:
   - Ensure service name in Ranger matches `ozone.om.ranger.service`
   - Verify OM principal is authorized in Ranger

3. For SIMPLE auth:
   - Verify username/password are correct
   - Check Ranger user exists and has service admin privileges

**Problem 2: Sync Not Running**

Symptoms:
- No sync logs in OM logs
- Ranger policies not updated despite OM DB changes

Solutions:
1. Verify OM is leader (in HA):
   ```bash
   ozone admin om getserviceroles --service-id=omservice
   # Sync only runs on leader
   ```

2. Check sync configuration:
   ```bash
   ozone getconf -confKey ozone.om.multitenancy.ranger.sync.interval
   # Should return configured interval
   ```

3. Check for sync errors:
   ```bash
   grep "OMRangerBGSyncService" /var/log/ozone/om.log | grep ERROR
   ```

**Problem 3: Policies Not Applied**

Symptoms:
- User denied access despite being in role
- Policy exists in Ranger but not enforced

Solutions:
1. Verify policy is enabled in Ranger UI

2. Check Ranger plugin cache refresh:
   ```bash
   # Force plugin to reload policies
   # (Automatic after ranger.plugin.ozone.policy.pollIntervalMs)
   ```

3. Verify role membership:
   ```bash
   # In Ranger UI: Access Manager → Roles → <role-name>
   # Ensure user is listed as member
   ```

4. Check policy resources match request:
   ```bash
   # Policy: volume=tenant1, bucket=*, key=*
   # Request must be for: /tenant1/any-bucket/any-key
   ```

**Problem 4: Sync Creating Duplicate Policies**

Symptoms:
- Multiple policies with same name pattern
- Sync errors about policy conflicts

Solutions:
1. Manually clean duplicate policies in Ranger UI

2. Check policy labels:
   ```bash
   # Policies should have label: om-tenant-<tenant-name>
   # Sync only manages policies with Ozone labels
   ```

3. Reset sync state (DANGEROUS - only if necessary):
   ```bash
   # Stop OM
   # Delete sync state from OM DB
   ozone debug ldb --db=/data/om/db delete --key=<sync-key>
   # Restart OM
   # Sync will re-detect state from scratch
   ```

### Performance Tuning

**1. Sync Interval:**

- **Frequent sync** (e.g., 60s):
  - Pros: Faster consistency
  - Cons: More Ranger load, more OM overhead
  - Use case: Active multi-tenancy with frequent user changes

- **Infrequent sync** (e.g., 3600s):
  - Pros: Lower overhead
  - Cons: Delayed consistency (up to 1 hour)
  - Use case: Stable tenancy, infrequent user changes

**2. Ranger Plugin Cache:**

Configure in `ranger-ozone-security.xml`:
```xml
<property>
  <name>ranger.plugin.ozone.policy.pollIntervalMs</name>
  <value>30000</value>
  <description>Policy refresh interval (30s)</description>
</property>

<property>
  <name>ranger.plugin.ozone.policy.cache.dir</name>
  <value>/tmp/ozone/policycache</value>
  <description>Local policy cache directory</description>
</property>
```

**3. Connection Pooling:**

```xml
<property>
  <name>ozone.om.ranger.connection.timeout</name>
  <value>5s</value>
</property>

<property>
  <name>ozone.om.ranger.connection.request.timeout</name>
  <value>5s</value>
</property>
```

### Security Best Practices

**1. Use Kerberos Authentication:**
- Never use SIMPLE auth (username/password) in production
- Store keytabs securely with proper file permissions (600)
- Rotate keytabs periodically

**2. Enable SSL/TLS:**
- Use valid SSL certificates for Ranger
- Set `ozone.om.ranger.ignore.cert=false` in production
- Use TLS 1.2+ only

**3. Principle of Least Privilege:**
- OM principal should only have necessary Ranger permissions
- Don't grant global admin to OM principal
- Use service-specific admin role

**4. Audit Everything:**
- Enable Ranger audit logging to Solr/DB
- Retain audit logs for compliance (e.g., 90 days)
- Monitor for suspicious access patterns

**5. Secrets Management:**
- Store S3 secrets encrypted in OM DB
- Use Hadoop KMS or external KMS for key management
- Rotate S3 secrets periodically

---

## Summary

Apache Ozone's integration with Apache Ranger provides enterprise-grade authorization through:

1. **Pluggable Authorization**: IAccessAuthorizer interface allows swapping between Ranger and native implementations

2. **Ranger Client Integration**: RangerClient HTTP client communicates with Ranger REST API for policy/role CRUD operations

3. **Multi-Tenancy Support**: S3-compatible multi-tenant access with Ranger-backed policies and roles

4. **Background Synchronization**: OMRangerBGSyncService ensures eventual consistency between OM DB and Ranger

5. **Hierarchical Permission Model**: Volume → Bucket → Prefix → Key permission inheritance

6. **Flexible Authentication**: Supports both Kerberos (production) and SIMPLE (development) authentication methods

7. **Comprehensive Audit Trail**: All access decisions logged in Ranger audit system

This deep integration enables centralized access control, fine-grained permissions, and scalable multi-tenancy for large Ozone deployments.
