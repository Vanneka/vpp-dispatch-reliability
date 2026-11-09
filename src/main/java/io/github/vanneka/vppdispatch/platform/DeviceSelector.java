package io.github.vanneka.vppdispatch.platform;

import io.github.vanneka.vppdispatch.fleet.SimulatedDevice;
import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses which devices take part in a dispatch and how much each one delivers.
 *
 * <p>The rule is deliberately simple: use the devices with the most available
 * power first, until the target is covered. The last device chosen is asked only
 * for what is still needed. Real platforms weigh many more factors; this release
 * is about recording progress reliably, not about optimal selection.
 */
public final class DeviceSelector {

    public List<DeviceCommand> select(DispatchRequest request, List<SimulatedDevice> devices) {
        List<SimulatedDevice> largestFirst = new ArrayList<>(devices);
        largestFirst.sort(Comparator.comparingInt(SimulatedDevice::availableKw).reversed()
                .thenComparing(SimulatedDevice::deviceId));

        List<DeviceCommand> commands = new ArrayList<>();
        int remainingKw = request.targetKw();
        for (SimulatedDevice device : largestFirst) {
            if (remainingKw == 0) {
                break;
            }
            int shareKw = Math.min(device.availableKw(), remainingKw);
            commands.add(DeviceCommand.forDevice(request, device.deviceId(), shareKw));
            remainingKw -= shareKw;
        }

        if (remainingKw > 0) {
            throw new InsufficientCapacityException(request.targetKw(), request.targetKw() - remainingKw);
        }
        return commands;
    }
}
