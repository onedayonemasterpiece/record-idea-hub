import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;
import java.util.ArrayList;
import java.util.List;

/** Read-only metadata probe for the installed debuggable RC4; never invokes target methods. */
public final class ReadbackExceptionProbe {
    private static final String PREFIX = "com.onedayonemasterpiece.recordideahub.";
    private static String quoted(String value) {
        // All output is code metadata, never exception messages, locals, fields or token values.
        return "\"" + value.replaceAll("[^A-Za-z0-9_.$:<>-]", "_").substring(0, Math.min(value.length(), 180)) + "\"";
    }
    private static String location(Location location) {
        return location.declaringType().name() + "." + location.method().name() + ":" + location.lineNumber();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: probe PORT SECONDS");
        int port = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        if (port < 1 || port > 65535 || seconds < 1 || seconds > 120) throw new IllegalArgumentException("Invalid bounds");
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue("10000");
        VirtualMachine vm = connector.attach(arguments);
        try {
            ExceptionRequest request = vm.eventRequestManager().createExceptionRequest(null, true, false);
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            request.enable();
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(500);
                if (events == null) continue;
                try {
                    for (Event event : events) {
                        if (!(event instanceof ExceptionEvent exception)) continue;
                        Location caught = exception.catchLocation();
                        if (caught == null || !caught.declaringType().name().equals(PREFIX + "SyncEngine") ||
                            !caught.method().name().startsWith("run")) continue;
                        String type = exception.exception().referenceType().name();
                        if (type.endsWith("CancellationException")) continue;
                        List<String> frames = new ArrayList<>();
                        for (StackFrame frame : exception.thread().frames()) {
                            if (frame.location().declaringType().name().startsWith(PREFIX)) {
                                frames.add(quoted(location(frame.location())));
                                if (frames.size() == 6) break;
                            }
                        }
                        System.out.println("{\"result\":\"captured\",\"exception_type\":" + quoted(type) +
                            ",\"catch_location\":" + quoted(location(caught)) +
                            ",\"http_status\":null,\"frames\":[" + String.join(",", frames) + "]}");
                        return;
                    }
                } finally { events.resume(); }
            }
            System.out.println("{\"result\":\"no_matching_exception_within_window\"}");
        } catch (VMDisconnectedException disconnected) {
            System.out.println("{\"result\":\"target_disconnected\"}");
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException ignored) { }
        }
    }
}
