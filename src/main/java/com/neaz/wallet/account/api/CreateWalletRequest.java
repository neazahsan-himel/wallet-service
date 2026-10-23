package com.neaz.wallet.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank @Size(max = 64) String ownerId) {
}
