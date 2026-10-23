package com.neaz.wallet.transfer.application.port;

import com.neaz.wallet.transfer.domain.Transfer;

public interface TransferRepository {

    void insert(Transfer transfer);
}
