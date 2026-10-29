package io.nanpanel.core;

import static io.nanpanel.core.AuthorizationPolicy.ALLOW;
import static io.nanpanel.core.AuthorizationPolicy.DENY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nanpanel.core.AuthorizationPolicy.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorizationPolicyTest {

  private static Statement allow(List<String> actions, List<String> resources) {
    return new Statement(ALLOW, actions, resources);
  }

  private static Statement deny(List<String> actions, List<String> resources) {
    return new Statement(DENY, actions, resources);
  }

  @Test
  void anExactAllowGrantsOnlyThatActionAndResource() {
    var statements = List.of(allow(List.of("host:read"), List.of("host:h-1")));
    assertTrue(AuthorizationPolicy.permits(statements, "host:read", "host:h-1"));
    assertFalse(AuthorizationPolicy.permits(statements, "host:read", "host:h-2"));
    assertFalse(AuthorizationPolicy.permits(statements, "host:write", "host:h-1"));
  }

  @Test
  void wildcardActionsAndResourcesBroadenTheStatement() {
    assertTrue(AuthorizationPolicy.permits(
        List.of(allow(List.of("host:*"), List.of("host:*"))), "host:restart", "host:h-9"));
    assertTrue(AuthorizationPolicy.permits(
        List.of(allow(List.of("*"), List.of("*"))), "site:apply", "site:s-1"));
    assertFalse(AuthorizationPolicy.permits(
        List.of(allow(List.of("host:*"), List.of("host:*"))), "site:apply", "site:s-1"));
  }

  @Test
  void defaultIsDenyWhenNothingMatches() {
    assertFalse(AuthorizationPolicy.permits(List.of(), "host:read", "host:h-1"));
    assertFalse(AuthorizationPolicy.permits(null, "host:read", "host:h-1"));
  }

  @Test
  void anExplicitDenyBeatsABroaderAllow() {
    var statements = List.of(
        allow(List.of("host:*"), List.of("*")),
        deny(List.of("host:delete"), List.of("host:h-1")));
    assertTrue(AuthorizationPolicy.permits(statements, "host:restart", "host:h-1"));
    assertFalse(AuthorizationPolicy.permits(statements, "host:delete", "host:h-1"));
    assertTrue(AuthorizationPolicy.permits(statements, "host:delete", "host:h-2"));
  }

  @Test
  void aDenyOnAnotherResourceDoesNotBlock() {
    var statements = List.of(
        allow(List.of("host:read"), List.of("host:*")),
        deny(List.of("host:read"), List.of("host:h-2")));
    assertTrue(AuthorizationPolicy.permits(statements, "host:read", "host:h-1"));
    assertFalse(AuthorizationPolicy.permits(statements, "host:read", "host:h-2"));
  }

  @Test
  void malformedStatementsAreIgnoredRatherThanGranting() {
    var statements = List.of(
        new Statement("MAYBE", List.of("*"), List.of("*")),
        new Statement(ALLOW, List.of(), List.of("*")),
        new Statement(ALLOW, List.of("Host Read"), List.of("*")),
        new Statement(ALLOW, List.of("*"), List.of("host h-1")));
    assertFalse(AuthorizationPolicy.permits(statements, "host:read", "host:h-1"));
  }

  @Test
  void malformedRequestsAreDenied() {
    var statements = List.of(allow(List.of("*"), List.of("*")));
    assertFalse(AuthorizationPolicy.permits(statements, "hostread", "host:h-1"));
    assertFalse(AuthorizationPolicy.permits(statements, "host:read", ""));
    assertFalse(AuthorizationPolicy.permits(statements, "host:read", null));
    assertFalse(AuthorizationPolicy.permits(statements, null, "host:h-1"));
  }

  @Test
  void statementValidationMatchesTheDocumentedGrammar() {
    assertTrue(AuthorizationPolicy.validStatement(allow(List.of("host:read"), List.of("host:h-1"))));
    assertTrue(AuthorizationPolicy.validStatement(allow(List.of("host:*"), List.of("*"))));
    assertFalse(AuthorizationPolicy.validStatement(allow(List.of("host"), List.of("*"))));
    assertFalse(AuthorizationPolicy.validStatement(allow(List.of("*"), List.of("host:*:x"))));
    assertFalse(AuthorizationPolicy.validStatement(allow(List.of("*"), List.of("HOST:x"))));
    assertFalse(AuthorizationPolicy.validStatement(null));
  }

  @Test
  void duplicateStatementsDoNotChangeTheOutcome() {
    var single = allow(List.of("host:read"), List.of("host:h-1"));
    assertTrue(AuthorizationPolicy.permits(List.of(single, single), "host:read", "host:h-1"));
  }
}
