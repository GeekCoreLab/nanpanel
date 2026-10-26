# ADR-016: Application virtual-network data plane

- Status: Implemented in source; isolated-cluster acceptance remains open.
- Date: 2026-10-26

## Context

Application topology currently stores a per-application graph and Deployment
attachment intent. A saved Revision does not attach Pod interfaces or change
Service endpoints. The requirement is for selected Kubernetes Service traffic
to traverse the configured virtual devices while keeping the cluster's default
CNI operational.

## Decision

1. Keep the default CNI as the Pod's primary network. Use Multus and a
   namespace-scoped `NetworkAttachmentDefinition` for the application's
   secondary interface.
2. Start with one explicitly mapped, ready Kubernetes Node and its registered
   Host Helper. Pin attached Deployments to that Node for the duration of the
   attachment. Do not silently move workloads across Nodes.
3. Connect the secondary CNI bridge to the per-application topology namespace
   with an owned veth pair. The host-side bridge has no host address. Add only
   the application network's connected CIDR route needed for Service traffic;
   do not change host interfaces, the default route, or management routes.
   Configure the bridge CNI with `portIsolation: true` so Pods cannot bypass
   the virtual-device path by switching directly to sibling Pod ports. Apply
   remains incomplete until attached Pods become Ready with the expected
   secondary interface; operators can then compensate the partial operation.
4. Publish secondary Pod IPs through NaNPanel-managed EndpointSlices for
   explicitly attached application Services. Reconcile endpoint membership
   from Pod readiness and Multus network status. Save, plan, apply, verify, and
   compensate remain separate states; saving a graph never applies it.
5. Keep the virtual network separate from the primary Pod network. Host-local
   traffic to the secondary network must be filtered, while traffic forwarded
   from a Kubernetes Service to a secondary EndpointSlice is allowed.
6. VXLAN is the preferred transport when an application topology spans mapped
   Nodes: use a per-application VNI and the mapped Nodes' underlay addresses,
   with peer filtering and no host IP on the application bridge. The initial
   single-Node boundary has no remote VTEP; its data path uses the isolated
   veth/bridge attachment without unnecessary VXLAN encapsulation. Cross-Node
   Apply and its UDP underlay checks are not part of the first acceptance gate.
7. Never install Multus or mutate a Kubernetes cluster as a side effect of
   saving a topology or publishing the control-plane software. Installation
   and Provider Apply are separate explicit actions; live packet acceptance
   requires an isolated cluster with Multus and the namespace-scoped runtime
   permissions. A confirmed control-plane release to `k3s-1` does not itself
   apply any application's topology or alter its Pod/Service data plane.

## Consequences

- A topology Revision is desired state only. The separate runtime API persists
  Apply, Verify, and Compensate state, Helper operation ID, CIDR reservations,
  and the Kubernetes preimage.
- Applying a network rolls attached Deployments, pins them to the mapped Node,
  creates owned NADs, and patches selected Services to use managed EndpointSlices.
  Compensation restores only runtime-owned fields and waits for attached Pods
  to leave the secondary network before removing host resources.
- The Root Helper owns only the named bridge, veths, exact CIDR route, and
  scoped filtering rules. Apply must fail before mutation if ownership,
  route-conflict, or cleanup preconditions cannot be established.
- Same-Node forwarding can be validated independently. It does not establish
  cross-Node VXLAN behavior or production acceptance.

## Verification boundary

Source compilation does not establish live packet acceptance. Required
isolated-cluster evidence includes a real Pod secondary interface,
Service traffic whose EndpointSlice resolves to that secondary IP, packets
observed through the selected virtual-device path, blocked host-originated
access to the application network, default-CNI health, and unchanged default
and management routes. Cross-Node VXLAN acceptance additionally requires
two mapped Nodes and a bidirectional packet path through the application VNI.
