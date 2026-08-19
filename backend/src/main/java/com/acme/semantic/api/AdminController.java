package com.acme.semantic.api;

import com.acme.semantic.auth.AdminIdentityService;
import com.acme.semantic.auth.AdminIdentityService.DomainGrant;
import com.acme.semantic.auth.AdminIdentityService.RoleView;
import com.acme.semantic.auth.AdminIdentityService.ServiceAccountSecret;
import com.acme.semantic.auth.AdminIdentityService.ServiceAccountView;
import com.acme.semantic.auth.AdminIdentityService.UserView;
import com.acme.semantic.core.SemanticCapability;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
  private final AdminIdentityService identities;

  public AdminController(AdminIdentityService identities) {
    this.identities = identities;
  }

  @GetMapping("/users")
  public List<UserView> users(HttpServletRequest request) {
    return identities.users(ApiSecurityFilter.principal(request));
  }

  @GetMapping("/users/{id}")
  public UserView user(@PathVariable long id, HttpServletRequest request) {
    return identities.user(ApiSecurityFilter.principal(request), id);
  }

  @PostMapping("/users")
  @ResponseStatus(HttpStatus.CREATED)
  public UserView createUser(@RequestBody CreateUser body, HttpServletRequest request) {
    return identities.createUser(
        ApiSecurityFilter.principal(request),
        body.username(),
        body.password(),
        body.displayName(),
        body.email());
  }

  @PutMapping("/users/{id}")
  public UserView updateUser(
      @PathVariable long id, @RequestBody UpdateUser body, HttpServletRequest request) {
    return identities.updateUser(
        ApiSecurityFilter.principal(request),
        id,
        body.displayName(),
        body.email(),
        body.enabled());
  }

  @DeleteMapping("/users/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteUser(@PathVariable long id, HttpServletRequest request) {
    identities.deleteUser(ApiSecurityFilter.principal(request), id);
  }

  @PutMapping("/users/{id}/roles")
  public UserView setUserRoles(
      @PathVariable long id, @RequestBody UserRoles body, HttpServletRequest request) {
    return identities.setUserRoles(
        ApiSecurityFilter.principal(request), id, body.roleIds());
  }

  @PostMapping("/users/{id}/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(
      @PathVariable long id, @RequestBody ResetPassword body, HttpServletRequest request) {
    identities.resetPassword(
        ApiSecurityFilter.principal(request), id, body.newPassword());
  }

  @GetMapping("/roles")
  public List<RoleView> roles(HttpServletRequest request) {
    return identities.roles(ApiSecurityFilter.principal(request));
  }

  @GetMapping("/roles/{id}")
  public RoleView role(@PathVariable long id, HttpServletRequest request) {
    return identities.role(ApiSecurityFilter.principal(request), id);
  }

  @PostMapping("/roles")
  @ResponseStatus(HttpStatus.CREATED)
  public RoleView createRole(@RequestBody CreateRole body, HttpServletRequest request) {
    return identities.createRole(
        ApiSecurityFilter.principal(request), body.name(), body.description(), body.admin());
  }

  @PutMapping("/roles/{id}")
  public RoleView updateRole(
      @PathVariable long id, @RequestBody UpdateRole body, HttpServletRequest request) {
    return identities.updateRole(
        ApiSecurityFilter.principal(request), id, body.description(), body.admin());
  }

  @DeleteMapping("/roles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRole(@PathVariable long id, HttpServletRequest request) {
    identities.deleteRole(ApiSecurityFilter.principal(request), id);
  }

  @PutMapping("/roles/{id}/grants")
  public RoleView setRoleGrants(
      @PathVariable long id, @RequestBody RoleGrants body, HttpServletRequest request) {
    return identities.setRoleGrants(
        ApiSecurityFilter.principal(request), id, body.grants());
  }

  @PutMapping("/roles/{id}/capabilities")
  public RoleView setRoleCapabilities(
      @PathVariable long id, @RequestBody RoleCapabilities body, HttpServletRequest request) {
    return identities.setRoleCapabilities(
        ApiSecurityFilter.principal(request), id, body.capabilities());
  }

  @GetMapping("/service-accounts")
  public List<ServiceAccountView> serviceAccounts(HttpServletRequest request) {
    return identities.serviceAccounts(ApiSecurityFilter.principal(request));
  }

  @GetMapping("/service-accounts/{id}")
  public ServiceAccountView serviceAccount(@PathVariable long id, HttpServletRequest request) {
    return identities.serviceAccount(ApiSecurityFilter.principal(request), id);
  }

  @PostMapping("/service-accounts")
  @ResponseStatus(HttpStatus.CREATED)
  public ServiceAccountSecret createServiceAccount(
      @RequestBody CreateServiceAccount body, HttpServletRequest request) {
    return identities.createServiceAccount(
        ApiSecurityFilter.principal(request), body.name(), body.roleId());
  }

  @PutMapping("/service-accounts/{id}")
  public ServiceAccountView updateServiceAccount(
      @PathVariable long id,
      @RequestBody UpdateServiceAccount body,
      HttpServletRequest request) {
    return identities.updateServiceAccount(
        ApiSecurityFilter.principal(request), id, body.roleId(), body.enabled());
  }

  @PostMapping("/service-accounts/{id}/rotate")
  public ServiceAccountSecret rotateServiceAccount(
      @PathVariable long id, HttpServletRequest request) {
    return identities.rotateServiceAccount(ApiSecurityFilter.principal(request), id);
  }

  @DeleteMapping("/service-accounts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteServiceAccount(@PathVariable long id, HttpServletRequest request) {
    identities.deleteServiceAccount(ApiSecurityFilter.principal(request), id);
  }

  public record CreateUser(
      String username, String password, String displayName, String email) {}

  public record UpdateUser(String displayName, String email, boolean enabled) {}

  public record UserRoles(Set<Long> roleIds) {}

  public record ResetPassword(String newPassword) {}

  public record CreateRole(String name, String description, boolean admin) {}

  public record UpdateRole(String description, boolean admin) {}

  public record RoleGrants(List<DomainGrant> grants) {}

  public record RoleCapabilities(Set<SemanticCapability> capabilities) {}

  public record CreateServiceAccount(String name, long roleId) {}

  public record UpdateServiceAccount(long roleId, boolean enabled) {}
}
