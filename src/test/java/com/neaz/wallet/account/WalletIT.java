package com.neaz.wallet.account;

import com.neaz.wallet.account.domain.SystemAccounts;
import com.neaz.wallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletIT extends AbstractIntegrationTest {

    @Test
    void createdWalletStartsActiveWithZeroIqd() {
        var created = http.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"ownerId": "customer-42"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.ownerId").isEqualTo("customer-42")
                .jsonPath("$.currency").isEqualTo("IQD")
                .jsonPath("$.balance").isEqualTo(0)
                .jsonPath("$.status").isEqualTo("ACTIVE")
                .jsonPath("$.createdAt").exists()
                .returnResult();

        String location = created.getResponseHeaders().getLocation().toString();
        String id = location.substring(location.lastIndexOf('/') + 1);
        assertThat(UUID.fromString(id).version()).isEqualTo(7);

        http.get().uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.balance").isEqualTo(0);
    }

    @Test
    void getShowsStoredBalanceAndStatus() {
        UUID id = data.walletWithBalance(300_000);
        data.freeze(id);

        http.get().uri("/api/v1/wallets/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.balance").isEqualTo(300_000)
                .jsonPath("$.status").isEqualTo("FROZEN");
    }

    @Test
    void getReturns404_whenWalletDoesNotExist() {
        http.get().uri("/api/v1/wallets/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("WALLET_NOT_FOUND")
                .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void getReturns404_forSystemAccounts() {
        for (UUID systemAccount : new UUID[] {SystemAccounts.FEE, SystemAccounts.CASH_IN}) {
            http.get().uri("/api/v1/wallets/{id}", systemAccount)
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("WALLET_NOT_FOUND");
        }
    }

    @Test
    void getReturns400_whenIdIsNotAUuid() {
        http.get().uri("/api/v1/wallets/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void createReturns400_whenOwnerIdIsBlankOrMissing() {
        for (String body : new String[] {"{\"ownerId\": \"  \"}", "{}"}) {
            http.post().uri("/api/v1/wallets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                    .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("ownerId"));
        }
        assertThat(customerWalletCount()).isZero();
    }

    @Test
    void createReturns400_whenOwnerIdIsTooLong() {
        http.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"ownerId\": \"" + "x".repeat(65) + "\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void createReturns400_whenBodyIsNotJson() {
        http.post().uri("/api/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"ownerId\": ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    private long customerWalletCount() {
        return jdbc.sql("SELECT COUNT(*) FROM accounts WHERE type = 'CUSTOMER'").query(Long.class).single();
    }
}
