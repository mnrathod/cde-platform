package com.cde.platform.tenancy;

import com.cde.platform.conversion.ConversionCallers;
import com.cde.platform.model.Tenant;
import com.cde.platform.repository.TenantRepository;
import com.cde.platform.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * This platform's answer to "who is calling": a caller is a tenant.
 *
 * <p>Deliberately on this side of the boundary. Putting it in the conversion
 * package would compile perfectly well and quietly undo the point — the
 * conversion service would import {@code Tenant}, {@code TenantRepository} and
 * {@code TenantContext} again, and travel with all three when it is extracted.
 * {@code ConversionPackageBoundaryTest} fails if that happens.
 *
 * <p>The mapping is one line each way because the two concepts genuinely line
 * up here. That will not be true of every host, which is the reason the
 * interface exists rather than the conversion service simply using a tenant id
 * and calling it a caller id.
 */
@Component
public class TenantConversionCallers implements ConversionCallers {

    private final TenantRepository tenants;
    private final UserRepository users;

    public TenantConversionCallers(TenantRepository tenants, UserRepository users) {
        this.tenants = tenants;
        this.users = users;
    }

    @Override
    public long requireCurrentCallerId() {
        return TenantContext.requireTenantId();
    }

    @Override
    public long requireSubmitterId(String username) {
        return users.findByUsername(username)
            .orElseThrow(() -> new IllegalStateException(
                "An authenticated principal has no user record."))
            .getId();
    }

    @Override
    public <T> T callAsCaller(long callerId, Supplier<T> work) {
        return TenantContext.callAsTenant(callerId, work);
    }

    @Override
    public void runAsCaller(long callerId, Runnable work) {
        TenantContext.runAsTenant(callerId, work);
    }

    @Override
    public List<Long> knownCallerIds() {
        return tenants.findAll().stream().map(Tenant::getId).toList();
    }
}
