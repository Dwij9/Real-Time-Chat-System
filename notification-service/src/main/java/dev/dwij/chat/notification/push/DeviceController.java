package dev.dwij.chat.notification.push;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Device registration, called by the mobile apps on launch and on token rotation. */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    public record DeviceRegistration(String userId, String platform, String token) {
    }

    private final DeviceTokenStore tokens;

    public DeviceController(DeviceTokenStore tokens) {
        this.tokens = tokens;
    }

    @PostMapping
    public ResponseEntity<Void> register(@RequestBody DeviceRegistration registration) {
        tokens.register(registration.userId(), registration.platform(), registration.token());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(@RequestBody DeviceRegistration registration) {
        tokens.unregister(registration.userId(), registration.platform(), registration.token());
        return ResponseEntity.noContent().build();
    }
}
