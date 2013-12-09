package org.ovirt.engine.core.bll.validator;

import org.ovirt.engine.core.common.businessentities.ArchitectureType;
import org.ovirt.engine.core.common.businessentities.DiskInterface;
import org.ovirt.engine.core.common.osinfo.OsRepository;
import org.ovirt.engine.core.common.utils.SimpleDependecyInjector;
import org.ovirt.engine.core.compat.Version;

import java.util.List;

public class VmValidationUtils {

    /**
     * Check if the memory size is within the correct limits (as per the configuration), taking into account the
     * OS type.
     *
     * @param osId The OS identifier.
     * @param memSizeInMB The memory size to validate.
     *
     * @return Is the memory within the configured limits or not.
     */
    public static boolean isMemorySizeLegal(int osId, int memSizeInMB, Version clusterVersion) {
        return memSizeInMB >= getMinMemorySizeInMb(osId, clusterVersion) && memSizeInMB <= getMaxMemorySizeInMb(osId, clusterVersion);
    }

    /**
     * Check if the OS type is supported by the architecture type (as per the configuration).
     *
     * @param osId The OS identifier.
     * @param architectureType The architecture type to validate.
     *
     * @return If the OS type is supported.
     */
    public static boolean isOsTypeSupported(int osId, ArchitectureType architectureType) {
        return architectureType == getOsRepository().getArchitectureFromOS(osId);
    }

    /**
     * Check if the OS type support VirtIO-SCSI.
     *
     * @param osId The OS identifier.
     *
     * @return If the OS type is supported.
     */
    public static boolean isOsSupportedForVirtIoScsi(int osId, Version clusterVersion) {
        List<String> diskInterfaces = getOsRepository().getDiskInterfaces(osId, clusterVersion);
        return diskInterfaces.contains(DiskInterface.VirtIO_SCSI.toString());
    }

    /**
     * Get the configured minimum VM memory size allowed.
     *
     * @return The minimum VM memory size allowed (as per configuration).
     */
    public static Integer getMinMemorySizeInMb(int osId, Version version) {
        return getOsRepository().getMinimumRam(osId, version);
    }

    /**
     * Get the configured maximum VM memory size for this OS type.
     *
     * @param osId The type of OS to get the maximum memory for.
     *
     * @return The maximum VM memory setting for this OS (as per configuration).
     */
    public static Integer getMaxMemorySizeInMb(int osId, Version clusterVersion) {
        return getOsRepository().getMaximumRam(osId, clusterVersion);
    }

    private static OsRepository getOsRepository() {
        return SimpleDependecyInjector.getInstance().get(OsRepository.class);
    }
}
