package org.ovirt.engine.core.bll.network.host;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.ovirt.engine.core.bll.validator.ValidationResultMatchers.failsWith;
import static org.ovirt.engine.core.bll.validator.ValidationResultMatchers.isValid;
import static org.ovirt.engine.core.utils.MockConfigRule.mockConfig;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.ovirt.engine.core.bll.ValidationResult;
import org.ovirt.engine.core.bll.network.VmInterfaceManager;
import org.ovirt.engine.core.bll.network.cluster.ManagementNetworkUtil;
import org.ovirt.engine.core.bll.validator.HostInterfaceValidator;
import org.ovirt.engine.core.common.action.HostSetupNetworksParameters;
import org.ovirt.engine.core.common.businessentities.BusinessEntityMap;
import org.ovirt.engine.core.common.businessentities.Entities;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.network.Bond;
import org.ovirt.engine.core.common.businessentities.network.Network;
import org.ovirt.engine.core.common.businessentities.network.NetworkAttachment;
import org.ovirt.engine.core.common.businessentities.network.VdsNetworkInterface;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.utils.customprop.SimpleCustomPropertiesUtil;
import org.ovirt.engine.core.common.utils.customprop.ValidationError;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.compat.Version;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.network.NetworkAttachmentDao;
import org.ovirt.engine.core.dao.network.NetworkClusterDao;
import org.ovirt.engine.core.dao.network.NetworkDao;
import org.ovirt.engine.core.utils.MockConfigRule;
import org.ovirt.engine.core.utils.ReplacementUtils;

@RunWith(MockitoJUnitRunner.class)
public class HostSetupNetworksValidatorTest {

    private VDS host;
    private ManagementNetworkUtil managementNetworkUtil;

    @Mock
    private NetworkDao networkDaoMock;

    @Mock
    private NetworkAttachmentDao networkAttachmentDaoMock;

    @Mock
    private NetworkClusterDao networkClusterDaoMock;

    @Mock
    private VdsDao vdsDaoMock;

    private Bond bond;

    @ClassRule
    public static final MockConfigRule mcr = new MockConfigRule(
        mockConfig(ConfigValues.NetworkCustomPropertiesSupported, Version.v3_4.toString(), false),
        mockConfig(ConfigValues.NetworkCustomPropertiesSupported, Version.v3_5.toString(), true));

    @Before
    public void setUp() throws Exception {
        host = new VDS();
        host.setId(Guid.newGuid());
        host.setVdsGroupCompatibilityVersion(Version.v3_5);

        managementNetworkUtil = Mockito.mock(ManagementNetworkUtil.class);

        bond = new Bond();
        bond.setId(Guid.newGuid());
    }

    @Test
    public void testNetworksOnNicMatchMtuWhenNoNetworksAreProvided() throws Exception {
        Map<String, List<Network>> networksOnNics =
            Collections.singletonMap("nicName", Collections.<Network> emptyList());

        assertThat(createHostSetupNetworksValidator().validateMtu(networksOnNics),
            isValid());
    }

    @Test
    public void testNetworksOnNicMatchMtu() throws Exception {
        List<Network> networks = Arrays.asList(createNetwork(1, false),
            createNetwork(1, false));

        Map<String, List<Network>> networksOnNics = Collections.singletonMap("nicName", networks);

        assertThat(createHostSetupNetworksValidator().validateMtu(networksOnNics), isValid());
    }

    @Test
    public void testNetworksOnNicMatchMtuNetworkMtuDoesNotMatch() throws Exception {

        List<Network> networks = Arrays.asList(createNetwork(1, false),
            createNetwork(2, false));

        Map<String, List<Network>> networksOnNics = Collections.singletonMap("nicName", networks);

        HostSetupNetworksValidator hostSetupNetworksValidator = createHostSetupNetworksValidator();
        assertThat(hostSetupNetworksValidator.validateMtu(networksOnNics),
            failsWith(EngineMessage.NETWORK_MTU_DIFFERENCES));
    }

    /**
     * this is probably not a valid scenario, but validation method allows this.
     */
    @Test
    public void testNetworksOnNicMatchMtuIgnoreMtuDifferenceWhenBothNetworksAreVmNetworks() throws Exception {
        List<Network> networks = Arrays.asList(createNetwork(1, true),
            createNetwork(2, true));

        Map<String, List<Network>> networksOnNics = Collections.singletonMap("nicName", networks);

        assertThat(createHostSetupNetworksValidator().validateMtu(networksOnNics), isValid());
    }

    private Network createNetwork(int mtu, boolean isVmNetwork) {
        Network network = new Network();
        network.setId(Guid.newGuid());
        network.setMtu(mtu);
        network.setVmNetwork(isVmNetwork);
        return network;
    }

    @Test
    public void testNotRemovingLabeledNetworksReferencingUnlabeledNetworkRemovalIsOk() throws Exception {
        Network unlabeledNetwork = new Network();
        unlabeledNetwork.setId(Guid.newGuid());

        NetworkAttachment networkAttachment = createNetworkAttachment(unlabeledNetwork);

        HostSetupNetworksValidator validator =
            createHostSetupNetworksValidator(Collections.singletonList(unlabeledNetwork));
        assertThat(validator.notRemovingLabeledNetworks(networkAttachment, null), isValid());
    }

    @Test
    public void testNotRemovingLabeledNetworksWhenNicNameDoesNotReferenceExistingNicItsOkToRemove() throws Exception {
        Network labeledNetwork = new Network();
        labeledNetwork.setId(Guid.newGuid());
        labeledNetwork.setLabel("label");

        NetworkAttachment networkAttachment = createNetworkAttachment(labeledNetwork);
        networkAttachment.setNicName("noLongerExistingNicName");

        VdsNetworkInterface existingNic = new VdsNetworkInterface();
        existingNic.setName("existingNicName");
        Map<String, VdsNetworkInterface> existingNics = Entities.entitiesByName(Collections.singletonList(existingNic));

        HostSetupNetworksValidator validator =
            createHostSetupNetworksValidator(Collections.singletonList(labeledNetwork));
        assertThat(validator.notRemovingLabeledNetworks(networkAttachment, existingNics), isValid());
    }

    @Test
    public void testNotRemovingLabeledNetworksWhenRemovingLabeledNetworkUnrelatedToRemovedBond() throws Exception {
        String nicName = "nicName";
        String label = "label";

        Network labeledNetwork = new Network();
        labeledNetwork.setId(Guid.newGuid());
        labeledNetwork.setLabel(label);

        NetworkAttachment networkAttachment = createNetworkAttachment(labeledNetwork);
        networkAttachment.setNicName(nicName);

        VdsNetworkInterface existingNic = new VdsNetworkInterface();
        existingNic.setLabels(Collections.singleton(label));
        existingNic.setName(nicName);
        Map<String, VdsNetworkInterface> existingNics = Entities.entitiesByName(Collections.singletonList(existingNic));

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(
            new HostSetupNetworksParameters(host.getId()),
            Collections.singletonList(existingNic),
            Collections.<NetworkAttachment> emptyList(),
            new BusinessEntityMap<>(Collections.singletonList(labeledNetwork)));

        assertThat(validator.notRemovingLabeledNetworks(networkAttachment, existingNics), failsWith(
            EngineMessage.ACTION_TYPE_FAILED_CANNOT_REMOVE_LABELED_NETWORK_FROM_NIC));
    }

    @Test
    public void testNotMovingLabeledNetworkToDifferentNicWhenRemovingLabeledNetworkUnrelatedToRemovedBond() throws Exception {
        String label = "label";

        Network labeledNetwork = new Network();
        labeledNetwork.setId(Guid.newGuid());
        labeledNetwork.setLabel(label);

        VdsNetworkInterface existingNic = new VdsNetworkInterface();
        existingNic.setLabels(Collections.singleton(label));
        existingNic.setId(Guid.newGuid());

        VdsNetworkInterface existingNic2 = new VdsNetworkInterface();
        existingNic2.setId(Guid.newGuid());

        Guid attachmentId = Guid.newGuid();
        NetworkAttachment existingNetworkAttachment = createNetworkAttachment(labeledNetwork, attachmentId);
        existingNetworkAttachment.setNicId(existingNic.getId());

        NetworkAttachment updatedNetworkAttachment = createNetworkAttachment(labeledNetwork, attachmentId);
        updatedNetworkAttachment.setNicId(existingNic2.getId());

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Collections.singletonList(updatedNetworkAttachment));

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(
            params,
            Arrays.asList(existingNic, existingNic2),
            Collections.singletonList(existingNetworkAttachment),
            new BusinessEntityMap<>(Collections.singletonList(labeledNetwork))
        );

        assertThat(validator.notMovingLabeledNetworkToDifferentNic(updatedNetworkAttachment),
            failsWith(EngineMessage.ACTION_TYPE_FAILED_CANNOT_MOVE_LABELED_NETWORK_TO_ANOTHER_NIC,
                ReplacementUtils.createSetVariableString(HostSetupNetworksValidator.ACTION_TYPE_FAILED_CANNOT_MOVE_LABELED_NETWORK_TO_ANOTHER_NIC_ENTITY,
                    label)));
    }

    @Test
    public void testNotRemovingLabeledNetworksWhenLabelRelatedToRemovedBond() throws Exception {
        String label = "label";
        String nicName = "nicName";

        Network labeledNetwork = new Network();
        labeledNetwork.setId(Guid.newGuid());
        labeledNetwork.setLabel(label);

        NetworkAttachment networkAttachment = createNetworkAttachment(labeledNetwork);
        networkAttachment.setNicName(nicName);

        bond.setLabels(Collections.singleton(label));
        bond.setName(nicName);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedBonds(Collections.singleton(bond.getId()));

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(params,
            Collections.<VdsNetworkInterface> singletonList(bond),
            null,
            new BusinessEntityMap<>(Collections.singletonList(labeledNetwork)));
        assertThat(validator.notRemovingLabeledNetworks(networkAttachment,
            Entities.entitiesByName(Collections.<VdsNetworkInterface> singletonList(bond))), isValid());
    }

    @Test
    public void testValidRemovedBondsWhenNotRemovingAnyBond() throws Exception {
        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedBonds(Collections.<Guid> emptySet());

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(params, null);

        assertThat(validator.validRemovedBonds(Collections.<NetworkAttachment> emptyList()), isValid());
    }

    @Test
    public void testValidRemovedBondsWhenReferencedInterfaceIsNotBond() throws Exception {
        VdsNetworkInterface notABond = createNic("nicName");

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedBonds(Collections.singleton(notABond.getId()));

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(params,
            Collections.singletonList(notABond));

        assertThat(validator.validRemovedBonds(Collections.<NetworkAttachment> emptyList()),
            failsWith(EngineMessage.NETWORK_INTERFACE_IS_NOT_BOND));
    }

    @Test
    public void testValidRemovedBondsWhenReferencedInterfaceBondViaInexistingId() throws Exception {
        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        Guid idOfInexistingInterface = Guid.newGuid();
        params.setRemovedBonds(Collections.singleton(idOfInexistingInterface));

        HostSetupNetworksValidator validator = new HostSetupNetworksValidatorBuilder()
            .setHost(host)
            .setParams(params)
            .build();

        assertThat(validator.validRemovedBonds(Collections.<NetworkAttachment> emptyList()),
            failsWith(EngineMessage.NETWORK_BOND_NOT_EXISTS));     //TODO MM: fix variable replacements in translations patch.
    }

    @Test
    public void testValidRemovedBondsWhenBondIsRequired() throws Exception {
        String nicName = "nicName";
        bond.setName(nicName);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedBonds(Collections.singleton(bond.getId()));

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(params,
            Collections.<VdsNetworkInterface> singletonList(bond));

        NetworkAttachment requiredNetworkAttachment = new NetworkAttachment();
        requiredNetworkAttachment.setNicName(nicName);

        assertThat(validator.validRemovedBonds(Collections.singletonList(requiredNetworkAttachment)),
            failsWith(EngineMessage.BOND_USED_BY_NETWORK_ATTACHMENTS));
    }

    @Test
    public void testValidRemovedBondsWhenBondIsNotRequired() throws Exception {
        String nicName = "nicName";
        bond.setName(nicName);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedBonds(Collections.singleton(bond.getId()));

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(params,
            Collections.<VdsNetworkInterface> singletonList(bond));

        assertThat(validator.validRemovedBonds(Collections.<NetworkAttachment> emptyList()), isValid());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testGetAttachmentsToConfigureWhenNoChangesWereSent() throws Exception {
        Network networkA = createNetworkWithName("networkA");
        Network networkB = createNetworkWithName("networkB");

        NetworkAttachment networkAttachmentA = createNetworkAttachment(networkA);
        NetworkAttachment networkAttachmentB = createNetworkAttachment(networkB);

        HostSetupNetworksValidator validator = new HostSetupNetworksValidatorBuilder()
            .setHost(host)
            .setParams(new HostSetupNetworksParameters(host.getId()))
            .setExistingAttachments(Arrays.asList(networkAttachmentA, networkAttachmentB))
            .build();

        Collection<NetworkAttachment> attachmentsToConfigure = validator.getAttachmentsToConfigure();
        assertThat(attachmentsToConfigure.size(), is(2));
        assertThat(attachmentsToConfigure.contains(networkAttachmentA), is(true));
        assertThat(attachmentsToConfigure.contains(networkAttachmentB), is(true));
    }

    @Test
    public void testGetAttachmentsToConfigureWhenUpdatingNetworkAttachments() throws Exception {
        Network networkA = createNetworkWithName("networkA");
        Network networkB = createNetworkWithName("networkB");

        NetworkAttachment networkAttachmentA = createNetworkAttachment(networkA);
        NetworkAttachment networkAttachmentB = createNetworkAttachment(networkB);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Arrays.asList(networkAttachmentA, networkAttachmentB));

        HostSetupNetworksValidator validator = new HostSetupNetworksValidatorBuilder()
            .setHost(host)
            .setParams(params)
            .setExistingAttachments(Arrays.asList(networkAttachmentA, networkAttachmentB))
            .build();

        Collection<NetworkAttachment> attachmentsToConfigure = validator.getAttachmentsToConfigure();
        assertThat(attachmentsToConfigure.size(), is(2));
        assertThat(attachmentsToConfigure.contains(networkAttachmentA), is(true));
        assertThat(attachmentsToConfigure.contains(networkAttachmentB), is(true));
    }

    @Test
    public void testGetAttachmentsToConfigureWhenRemovingNetworkAttachments() throws Exception {
        Network networkA = createNetworkWithName("networkA");
        Network networkB = createNetworkWithName("networkB");

        NetworkAttachment networkAttachmentA = createNetworkAttachment(networkA);
        NetworkAttachment networkAttachmentB = createNetworkAttachment(networkB);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Collections.singletonList(networkAttachmentB));
        params.setRemovedNetworkAttachments(Collections.singleton(networkAttachmentA.getId()));
        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(params,
            Collections.<VdsNetworkInterface> emptyList(),
            Arrays.asList(networkAttachmentA, networkAttachmentB),
            null);

        Collection<NetworkAttachment> attachmentsToConfigure = validator.getAttachmentsToConfigure();
        assertThat(attachmentsToConfigure.size(), is(1));
        assertThat(attachmentsToConfigure.contains(networkAttachmentA), is(false));
        assertThat(attachmentsToConfigure.contains(networkAttachmentB), is(true));
    }

    @Test
    public void testGetAttachmentsToConfigureWhenAddingNewNetworkAttachments() throws Exception {
        Network networkA = createNetworkWithName("networkA");
        Network networkB = createNetworkWithName("networkB");

        NetworkAttachment networkAttachmentA = createNetworkAttachment(networkA, null);
        NetworkAttachment networkAttachmentB = createNetworkAttachment(networkB, null);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Arrays.asList(networkAttachmentA, networkAttachmentB));
        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(params,
            Collections.<VdsNetworkInterface> emptyList(),
            Collections.<NetworkAttachment> emptyList(),
            null);

        Collection<NetworkAttachment> attachmentsToConfigure = validator.getAttachmentsToConfigure();
        assertThat(attachmentsToConfigure.size(), is(2));
        assertThat(attachmentsToConfigure.contains(networkAttachmentA), is(true));
        assertThat(attachmentsToConfigure.contains(networkAttachmentB), is(true));
    }

    private NetworkAttachment createNetworkAttachment(Network networkA) {
        return createNetworkAttachment(networkA, Guid.newGuid());
    }

    private NetworkAttachment createNetworkAttachment(Network networkA, Guid id) {
        NetworkAttachment networkAttachmentA = new NetworkAttachment();
        networkAttachmentA.setId(id);
        networkAttachmentA.setNetworkId(networkA.getId());
        return networkAttachmentA;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testValidateNotRemovingUsedNetworkByVmsWhenUsedByVms() throws Exception {
        String nameOfNetworkA = "networkA";
        String nameOfNetworkB = "networkB";
        Network networkA = createNetworkWithName(nameOfNetworkA);
        Network networkB = createNetworkWithName(nameOfNetworkB);

        VdsNetworkInterface nicA = createNic("nicA");
        VdsNetworkInterface nicB = createNic("nicB");

        NetworkAttachment networkAttachmentA = createNetworkAttachment(networkA);
        networkAttachmentA.setNicId(nicA.getId());
        NetworkAttachment networkAttachmentB = createNetworkAttachment(networkB);
        networkAttachmentB.setNicId(nicB.getId());

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedNetworkAttachments(new HashSet<>(Arrays.asList(networkAttachmentA.getId(),
            networkAttachmentB.getId())));

        HostSetupNetworksValidator validator = spy(createHostSetupNetworksValidator(params,
            Arrays.asList(nicA, nicB),
            Arrays.asList(networkAttachmentA, networkAttachmentB),
            new BusinessEntityMap<>(Arrays.asList(networkA, networkB))));

        VmInterfaceManager vmInterfaceManagerMock = mock(VmInterfaceManager.class);
        doReturn(vmInterfaceManagerMock).when(validator).getVmInterfaceManager();

        when(vmInterfaceManagerMock.findActiveVmsUsingNetworks(any(Guid.class), any(Collection.class)))
            .thenReturn(Arrays.asList(nameOfNetworkA, nameOfNetworkB));

        assertThat(validator.validateNotRemovingUsedNetworkByVms(),
            failsWith(EngineMessage.NETWORK_CANNOT_DETACH_NETWORK_USED_BY_VMS));

        ArgumentCaptor<Collection> collectionArgumentCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(vmInterfaceManagerMock).findActiveVmsUsingNetworks(eq(host.getId()), collectionArgumentCaptor.capture());
        assertThat(collectionArgumentCaptor.getValue().size(), is(2));
        assertThat(collectionArgumentCaptor.getValue().contains(nameOfNetworkA), is(true));
        assertThat(collectionArgumentCaptor.getValue().contains(nameOfNetworkB), is(true));
    }

    public VdsNetworkInterface createNic(String nicName) {
        VdsNetworkInterface existingNic = new VdsNetworkInterface();
        existingNic.setId(Guid.newGuid());
        existingNic.setName(nicName);
        return existingNic;
    }

    private Network createNetworkWithName(String nameOfNetworkA) {
        Network networkA = new Network();
        networkA.setName(nameOfNetworkA);
        networkA.setId(Guid.newGuid());
        return networkA;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testValidateNotRemovingUsedNetworkByVmsWhenNotUsedByVms() throws Exception {
        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());

        HostSetupNetworksValidator validator = spy(createHostSetupNetworksValidator(params,
            Collections.<VdsNetworkInterface> emptyList(),
            null,
            new BusinessEntityMap<>(Collections.<Network> emptyList())));

        VmInterfaceManager vmInterfaceManagerMock = mock(VmInterfaceManager.class);
        doReturn(vmInterfaceManagerMock).when(validator).getVmInterfaceManager();

        when(vmInterfaceManagerMock.findActiveVmsUsingNetworks(any(Guid.class), any(Collection.class)))
            .thenReturn(Collections.<String> emptyList());

        assertThat(validator.validateNotRemovingUsedNetworkByVms(), isValid());
    }

    @Test
    public void testNetworksUniquelyConfiguredOnHostWhenUniquelyConfigured() throws Exception {
        Network networkA = new Network();
        networkA.setId(Guid.newGuid());

        Network networkB = new Network();
        networkB.setId(Guid.newGuid());

        NetworkAttachment networkAttachmentA = createNetworkAttachment(networkA);
        NetworkAttachment networkAttachmentB = createNetworkAttachment(networkB);

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(Arrays.asList(networkA, networkB));

        assertThat(validator.networksUniquelyConfiguredOnHost(Arrays.asList(networkAttachmentA, networkAttachmentB)),
            isValid());
    }

    @Test
    public void testNetworksUniquelyConfiguredOnHostWhenNotUniquelyConfigured() throws Exception {
        Guid id = Guid.newGuid();
        Network networkA = new Network();
        networkA.setId(id);

        NetworkAttachment networkAttachment = createNetworkAttachment(networkA);
        NetworkAttachment networkAttachmentReferencingSameNetwork = createNetworkAttachment(networkA);

        HostSetupNetworksValidator validator = createHostSetupNetworksValidator(Collections.singletonList(networkA));

        assertThat(validator.networksUniquelyConfiguredOnHost(Arrays.asList(networkAttachment,
                networkAttachmentReferencingSameNetwork)),
            failsWith(EngineMessage.NETWORKS_ALREADY_ATTACHED_TO_IFACES));
    }

    @Test
    public void testValidModifiedBondsFailsWhenBondIsUnnamed() throws Exception {
        doTestValidModifiedBonds(new Bond(),
            new ValidationResult(EngineMessage.HOST_NETWORK_INTERFACE_NOT_EXIST),
            ValidationResult.VALID,
            new ValidationResult(EngineMessage.HOST_NETWORK_INTERFACE_NOT_EXIST),
            ValidationResult.VALID);
    }

    @Test
    public void testValidModifiedBondsFailsWhenReferencingExistingNonBondInterface() throws Exception {
        Bond bond = createBond();
        doTestValidModifiedBonds(bond,
            ValidationResult.VALID,
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_IS_NOT_BOND),
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_IS_NOT_BOND),
            ValidationResult.VALID);
    }

    @Test
    public void testValidModifiedBondsFailsWhenInsufficientNumberOfSlaves() throws Exception {
        Bond bond = createBond();
        doTestValidModifiedBonds(bond,
            ValidationResult.VALID,
            ValidationResult.VALID,
            new ValidationResult(EngineMessage.NETWORK_BONDS_INVALID_SLAVE_COUNT),
            ValidationResult.VALID);
    }

    @Test
    public void testValidModifiedBondsFailsWhenSlavesValidationFails() throws Exception {
        Bond bond = createBond();
        bond.setSlaves(Arrays.asList("slaveA", "slaveB"));
        doTestValidModifiedBonds(bond,
            ValidationResult.VALID,
            ValidationResult.VALID,
            /*this mocks validateModifiedBondSlaves to just verify, that caller method will behave ok, when
            validateModifiedBondSlaves return invalid result*/
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_ATTACHED_TO_NETWORK_CANNOT_BE_SLAVE),
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_ATTACHED_TO_NETWORK_CANNOT_BE_SLAVE));
    }

    @Test
    public void testValidModifiedBondsWhenAllOk() throws Exception {
        Bond bond = new Bond("bond1");
        bond.setSlaves(Arrays.asList("slaveA", "slaveB"));
        doTestValidModifiedBonds(bond,
            ValidationResult.VALID,
            ValidationResult.VALID,
            ValidationResult.VALID,
            ValidationResult.VALID);
    }

    private void doTestValidModifiedBonds(Bond bond,
        ValidationResult interfaceByNameExistValidationResult,
        ValidationResult interfaceIsBondValidationResult,
        ValidationResult expectedValidationResult,
        ValidationResult slavesValidationValidationResult) {
        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setBonds(Collections.singletonList(bond));

        HostSetupNetworksValidator validator =
            spy(createHostSetupNetworksValidator(params, null, null, null));

        HostInterfaceValidator hostInterfaceValidatorMock = mock(HostInterfaceValidator.class);
        when(hostInterfaceValidatorMock.interfaceByNameExists()).thenReturn(interfaceByNameExistValidationResult);
        when(hostInterfaceValidatorMock.interfaceIsBondOrNull()).thenReturn(interfaceIsBondValidationResult);

        doReturn(hostInterfaceValidatorMock).when(validator).createHostInterfaceValidator(any(VdsNetworkInterface.class));
        doReturn(slavesValidationValidationResult).when(validator).validateModifiedBondSlaves(any(Bond.class));

        if (expectedValidationResult.isValid()) {
            assertThat(validator.validNewOrModifiedBonds(), isValid());
        } else {
            assertThat(validator.validNewOrModifiedBonds(), failsWith(expectedValidationResult.getMessage()));
        }

        verify(hostInterfaceValidatorMock).interfaceByNameExists();

        //assert only if previous call was successful, otherwise this method was not called.
        if (interfaceByNameExistValidationResult.isValid()) {
            verify(hostInterfaceValidatorMock).interfaceIsBondOrNull();
        }
    }

    @Test
    public void testValidateModifiedBondSlavesWhenSlaveInterfaceDoesNotExist() throws Exception {
        Bond bond = createBond();
        bond.setSlaves(Arrays.asList("slaveA", "slaveB"));

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setBonds(Collections.singletonList(bond));

        doTestValidateModifiedBondSlaves(
            params,
            null,
            Collections.<NetworkAttachment> emptyList(),
            Collections.<Network> emptyList(),
            new ValidationResult(EngineMessage.HOST_NETWORK_INTERFACE_NOT_EXIST),
            ValidationResult.VALID,
            new ValidationResult(EngineMessage.HOST_NETWORK_INTERFACE_NOT_EXIST));
    }

    @Test
    public void testValidateModifiedBondSlavesWhenSlaveIsNotValid() throws Exception {
        Bond bond = createBond();
        bond.setSlaves(Arrays.asList("slaveA", "slaveB"));

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setBonds(Collections.singletonList(bond));

        doTestValidateModifiedBondSlaves(
            params,
            null,
            Collections.<NetworkAttachment> emptyList(),
            Collections.<Network> emptyList(),
            ValidationResult.VALID,
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_BOND_OR_VLAN_CANNOT_BE_SLAVE),
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_BOND_OR_VLAN_CANNOT_BE_SLAVE));
    }

    @Test
    public void testValidateModifiedBondSlavesWhenSlaveAlreadySlavesForDifferentBond() throws Exception {
        Bond bond = createBond("bond1");
        Bond differentBond = createBond("bond2");

        VdsNetworkInterface slaveA = createBondSlave(bond, "slaveA");
        VdsNetworkInterface slaveB = createBondSlave(differentBond, "slaveB");

        bond.setSlaves(Arrays.asList(slaveA.getName(), slaveB.getName()));

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setBonds(Collections.singletonList(bond));

        doTestValidateModifiedBondSlaves(
            params,
            Arrays.asList(bond, differentBond, slaveA, slaveB),
            Collections.<NetworkAttachment> emptyList(),
            Collections.<Network> emptyList(),
            ValidationResult.VALID,
            ValidationResult.VALID,
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_ALREADY_IN_BOND));
    }

    @Test
    public void testValidateModifiedBondSlavesWhenSlaveAlreadySlavesForDifferentBondWhichGetsRemoved() throws Exception {
        Bond bond = createBond("bondName");
        Bond differentBond = createBond("differentBond");

        VdsNetworkInterface slaveA = createBondSlave(bond, "slaveA");
        VdsNetworkInterface slaveB = createBondSlave(differentBond, "slaveB");

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedBonds(Collections.singleton(differentBond.getId()));

        bond.setSlaves(Arrays.asList(slaveA.getName(), slaveB.getName()));
        doTestValidateModifiedBondSlaves(
            params,
            Arrays.asList(bond, differentBond, slaveA, slaveB),
            Collections.<NetworkAttachment> emptyList(),
            Collections.<Network> emptyList(),
            ValidationResult.VALID,
            ValidationResult.VALID,
            ValidationResult.VALID);
    }

    @Test
    public void testValidateModifiedBondSlavesWhenSlaveAlreadySlavesForDifferentBondButItsDetachedFromItAsAPartOfRequest() throws Exception {
        Bond bond = createBond("bond1");
        Bond differentBond = createBond("bond2");

        VdsNetworkInterface slaveA = createBondSlave(bond, "slaveA");
        VdsNetworkInterface slaveB = createBondSlave(differentBond, "slaveB");
        VdsNetworkInterface slaveC = createBondSlave(differentBond, "slaveC");
        VdsNetworkInterface slaveD = createBondSlave(differentBond, "slaveD");

        bond.setSlaves(Arrays.asList(slaveA.getName(), slaveB.getName()));
        differentBond.setSlaves(Arrays.asList(slaveC.getName(), slaveD.getName()));

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setBonds(Arrays.asList(bond, differentBond));

        doTestValidateModifiedBondSlaves(
            params,
            Arrays.asList(bond, differentBond, slaveA, slaveB, slaveC, slaveD),
            Collections.<NetworkAttachment> emptyList(),
            Collections.<Network> emptyList(),
            ValidationResult.VALID,
            ValidationResult.VALID,
            ValidationResult.VALID);
    }

    public Bond createBond(String bondName) {
        Bond bond = new Bond();
        bond.setName(bondName);
        bond.setId(Guid.newGuid());
        return bond;
    }

    private Bond createBond() {
        return createBond("bond1");
    }

    @Test
    public void testValidateModifiedBondSlavesWhenSlaveHasNetworkAssignedWhichIsNotRemovedAsAPartOfRequest() throws Exception {
        Bond bond = createBond();

        Network networkBeingRemoved = new Network();
        networkBeingRemoved.setName("assignedNetwork");

        VdsNetworkInterface slaveA = createBondSlave(bond, "slaveA");
        slaveA.setNetworkName("assignedNetwork");
        VdsNetworkInterface slaveB = createBondSlave(bond, "slaveB");

        bond.setSlaves(Arrays.asList(slaveA.getName(), slaveB.getName()));

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setBonds(Collections.singletonList(bond));

        doTestValidateModifiedBondSlaves(
            params,
            Arrays.asList(bond, slaveA, slaveB),
            Collections.<NetworkAttachment> emptyList(),
            Collections.singletonList(networkBeingRemoved),
            ValidationResult.VALID,
            ValidationResult.VALID,
            new ValidationResult(EngineMessage.NETWORK_INTERFACE_ATTACHED_TO_NETWORK_CANNOT_BE_SLAVE));
    }

    @Test
    public void testValidateModifiedBondSlavesWhenSlaveHasNetworkAssignedWhichIsRemovedAsAPartOfRequest() throws Exception {
        Bond bond = createBond();

        Network networkBeingRemoved = new Network();
        networkBeingRemoved.setName("assignedNetwork");

        VdsNetworkInterface slaveA = createBondSlave(bond, "slaveA");
        slaveA.setNetworkName(networkBeingRemoved.getName());
        VdsNetworkInterface slaveB = createBondSlave(bond, "slaveB");

        NetworkAttachment removedNetworkAttachment = new NetworkAttachment();
        removedNetworkAttachment.setId(Guid.newGuid());
        removedNetworkAttachment.setNicName(slaveA.getName());

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setRemovedNetworkAttachments(Collections.singleton(removedNetworkAttachment.getId()));

        bond.setSlaves(Arrays.asList(slaveA.getName(), slaveB.getName()));
        doTestValidateModifiedBondSlaves(
            params,
            Arrays.asList(bond, slaveA, slaveB),
            Collections.singletonList(removedNetworkAttachment),
            Collections.singletonList(networkBeingRemoved),
            ValidationResult.VALID,
            ValidationResult.VALID,
            ValidationResult.VALID);
    }

    private void doTestValidateModifiedBondSlaves(HostSetupNetworksParameters params,
        List<VdsNetworkInterface> existingInterfaces,
        List<NetworkAttachment> existingAttachments,
        Collection<Network> networks,
        ValidationResult interfaceExistValidationResult,
        ValidationResult interfaceIsValidSlaveValidationResult,
        ValidationResult expectedValidationResult) {

        HostSetupNetworksValidator validator = spy(createHostSetupNetworksValidator(params,
            existingInterfaces,
            existingAttachments,
            new BusinessEntityMap<>(networks)));

        HostInterfaceValidator hostInterfaceValidatorMock = mock(HostInterfaceValidator.class);
        when(hostInterfaceValidatorMock.interfaceExists()).thenReturn(interfaceExistValidationResult);
        when(hostInterfaceValidatorMock.interfaceByNameExists()).thenReturn(interfaceExistValidationResult);
        when(hostInterfaceValidatorMock.interfaceIsValidSlave()).thenReturn(interfaceIsValidSlaveValidationResult);
        when(hostInterfaceValidatorMock.interfaceIsBondOrNull()).thenReturn(ValidationResult.VALID);        //TODO MM: test for this.

        doReturn(hostInterfaceValidatorMock).when(validator).createHostInterfaceValidator(any(VdsNetworkInterface.class));

        if (expectedValidationResult.isValid()) {
            assertThat(validator.validNewOrModifiedBonds(), isValid());
        } else {
            assertThat(validator.validNewOrModifiedBonds(), failsWith(expectedValidationResult.getMessage()));
        }
    }


    @Test
    public void testValidateCustomPropertiesWhenAttachmentDoesNotHaveCustomProperties() throws Exception {
        Network networkA = createNetworkWithName("networkA");
        Network networkB = createNetworkWithName("networkB");

        NetworkAttachment networkAttachmentA = createNetworkAttachment(networkA);
        networkAttachmentA.setProperties(null);
        NetworkAttachment networkAttachmentB = createNetworkAttachment(networkB);
        networkAttachmentB.setProperties(new HashMap<String, String>());

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Arrays.asList(networkAttachmentA, networkAttachmentB));

        HostSetupNetworksValidator validator =
            createHostSetupNetworksValidator(Arrays.asList(networkA, networkB), params);

        assertThat(validator.validateCustomProperties(SimpleCustomPropertiesUtil.getInstance(),
                Collections.<String, String> emptyMap(),
                Collections.<String, String> emptyMap()),
            isValid());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testValidateCustomPropertiesWhenCustomPropertiesFeatureIsNotSupported() throws Exception {
        Network networkA = createNetworkWithName("networkA");

        NetworkAttachment networkAttachment = createNetworkAttachment(networkA);

        Map<String, String> customProperties = new HashMap<>();
        customProperties.put("a", "b");
        networkAttachment.setProperties(customProperties);

        VDS host = new VDS();
        host.setVdsGroupCompatibilityVersion(Version.v3_4);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Collections.singletonList(networkAttachment));

        HostSetupNetworksValidator validator =
            spy(new HostSetupNetworksValidatorBuilder()
                .setHost(host)
                .setParams(params)
                .setNetworkBusinessEntityMap(new BusinessEntityMap<>(Collections.singletonList(networkA)))
                .build());

        assertThat(validator.validateCustomProperties(null,
                Collections.<String, String> emptyMap(),
                Collections.<String, String> emptyMap()),
            failsWith(EngineMessage.ACTION_TYPE_FAILED_NETWORK_CUSTOM_PROPERTIES_NOT_SUPPORTED));
    }

    @Test
    public void testValidateCustomPropertiesWhenCustomPropertyValidationFailed() throws Exception {
        Network networkA = createNetworkWithName("networkA");

        NetworkAttachment networkAttachment = createNetworkAttachment(networkA);

        Map<String, String> customProperties = new HashMap<>();
        customProperties.put("a", "b");
        networkAttachment.setProperties(customProperties);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Collections.singletonList(networkAttachment));

        HostSetupNetworksValidator validator =
            spy(new HostSetupNetworksValidatorBuilder()
                .setHost(host)
                .setParams(params)
                .setNetworkBusinessEntityMap(new BusinessEntityMap<>(Collections.singletonList(networkA)))
                .build());

        //this was added just because of DI issues with 'Backend.getInstance().getErrorsTranslator()' is 'spyed' method
        //noinspection unchecked
        doReturn(Collections.emptyList()).when(validator).translateErrorMessages(any(List.class));

        assertThat(validator.validateCustomProperties(SimpleCustomPropertiesUtil.getInstance(),
                Collections.<String, String> emptyMap(),
                Collections.<String, String> emptyMap()),
            failsWith(EngineMessage.ACTION_TYPE_FAILED_NETWORK_CUSTOM_PROPERTIES_BAD_INPUT));
    }

    @Test
    public void testValidateCustomProperties() throws Exception {
        Network networkA = createNetworkWithName("networkA");

        NetworkAttachment networkAttachment = createNetworkAttachment(networkA);

        Map<String, String> customProperties = new HashMap<>();
        customProperties.put("a", "b");
        networkAttachment.setProperties(customProperties);

        HostSetupNetworksParameters params = new HostSetupNetworksParameters(host.getId());
        params.setNetworkAttachments(Collections.singletonList(networkAttachment));

        HostSetupNetworksValidator validator =
            new HostSetupNetworksValidatorBuilder()
                .setHost(host)
                .setParams(params)
                .setNetworkBusinessEntityMap(new BusinessEntityMap<>(Collections.singletonList(networkA)))
                .build();

        //we do not test SimpleCustomPropertiesUtil here, we just state what happens if it does not find ValidationError
        SimpleCustomPropertiesUtil simpleCustomPropertiesUtilMock = mock(SimpleCustomPropertiesUtil.class);
        when(simpleCustomPropertiesUtilMock
            .validateProperties(any(Map.class), any(Map.class)))
            .thenReturn(Collections.<ValidationError>emptyList());

        assertThat(validator.validateCustomProperties(simpleCustomPropertiesUtilMock,
                Collections.<String, String> emptyMap(),
                Collections.<String, String> emptyMap()),
            isValid());
    }

    private VdsNetworkInterface createBondSlave(Bond bond, String slaveName) {
        VdsNetworkInterface slave = new VdsNetworkInterface();
        slave.setId(Guid.newGuid());
        slave.setName(slaveName);
        slave.setBondName(bond.getName());
        slave.setBonded(false);
        return slave;
    }

    private HostSetupNetworksValidator createHostSetupNetworksValidator() {
        return new HostSetupNetworksValidatorBuilder()
            .setHost(host)
            .setParams(new HostSetupNetworksParameters(host.getId()))
            .build();
    }

    private HostSetupNetworksValidator createHostSetupNetworksValidator(List<Network> networks) {
        return createHostSetupNetworksValidator(networks, new HostSetupNetworksParameters(host.getId()));
    }

    private HostSetupNetworksValidator createHostSetupNetworksValidator(List<Network> networks,
        HostSetupNetworksParameters params) {
        return new HostSetupNetworksValidatorBuilder()
            .setHost(host)
            .setParams(params)
            .setNetworkBusinessEntityMap(new BusinessEntityMap<>(networks))
            .build();
    }

    private HostSetupNetworksValidator createHostSetupNetworksValidator(HostSetupNetworksParameters params,
        List<VdsNetworkInterface> existingIfaces) {

        return new HostSetupNetworksValidatorBuilder()
            .setHost(host)
            .setParams(params)
            .setExistingInterfaces(existingIfaces)
            .build();
    }

    private HostSetupNetworksValidator createHostSetupNetworksValidator(HostSetupNetworksParameters params,
        List<VdsNetworkInterface> existingIfaces,
        List<NetworkAttachment> existingAttachments,
        BusinessEntityMap<Network> networkBusinessEntityMap) {

        return new HostSetupNetworksValidatorBuilder()
            .setHost(host)
            .setParams(params)
            .setExistingInterfaces(existingIfaces)
            .setExistingAttachments(existingAttachments)
            .setNetworkBusinessEntityMap(networkBusinessEntityMap)
            .build();
    }

    public class HostSetupNetworksValidatorBuilder {
        private VDS host;
        private HostSetupNetworksParameters params;
        private List<VdsNetworkInterface> existingInterfaces = Collections.emptyList();
        private List<NetworkAttachment> existingAttachments = Collections.emptyList();
        private BusinessEntityMap<Network> networkBusinessEntityMap = new BusinessEntityMap<>(Collections.<Network> emptyList());

        public HostSetupNetworksValidatorBuilder setHost(VDS host) {
            this.host = host;
            return this;
        }

        public HostSetupNetworksValidatorBuilder setParams(HostSetupNetworksParameters params) {
            this.params = params;
            return this;
        }

        public HostSetupNetworksValidatorBuilder setExistingInterfaces(List<VdsNetworkInterface> existingInterfaces) {
            this.existingInterfaces = existingInterfaces;
            return this;
        }

        public HostSetupNetworksValidatorBuilder setExistingAttachments(List<NetworkAttachment> existingAttachments) {
            this.existingAttachments = existingAttachments;
            return this;
        }

        public HostSetupNetworksValidatorBuilder setNetworkBusinessEntityMap(BusinessEntityMap<Network> networkBusinessEntityMap) {
            this.networkBusinessEntityMap = networkBusinessEntityMap;
            return this;
        }

        public HostSetupNetworksValidator build() {
            return new HostSetupNetworksValidator(host,
                params,
                existingInterfaces,
                existingAttachments,
                networkBusinessEntityMap,
                managementNetworkUtil,
                networkClusterDaoMock,
                networkAttachmentDaoMock,
                networkDaoMock,
                vdsDaoMock);
        }
    }
}
