package dev.subscout.platform;

import java.net.InetAddress;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * A local-first app with no login must not be reachable from the LAN. If someone binds it
 * to 0.0.0.0 they have to say so explicitly, and the README explains the consequence.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BindSafetyCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BindSafetyCheck.class);

    private final Environment environment;

    public BindSafetyCheck(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String address = environment.getProperty("server.address", "127.0.0.1");
        if (isLoopback(address)) {
            return;
        }
        boolean allowed = Boolean.parseBoolean(environment.getProperty("SUB_SCOUT_ALLOW_NON_LOOPBACK", "false"));
        if (!allowed) {
            throw new IllegalStateException(
                    "Refusing to bind to " + address + ": SubScout has no login and holds financial data. "
                            + "Use 127.0.0.1, or set SUB_SCOUT_ALLOW_NON_LOOPBACK=true if you understand the risk.");
        }
        log.warn("SubScout is bound to {} with no authentication. Anyone who can reach this port can read the ledger.",
                address);
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return true;
        }
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("::1") || normalized.equals("[::1]")) {
            return true;
        }
        try {
            return InetAddress.getByName(normalized).isLoopbackAddress();
        } catch (Exception unresolved) {
            return false;
        }
    }
}
