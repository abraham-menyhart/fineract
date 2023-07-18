/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.investor.cob.loan;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.cob.loan.LoanCOBBusinessStep;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.investor.config.InvestorModuleIsEnabledCondition;
import org.apache.fineract.investor.data.ExternalTransferStatus;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransfer;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransferRepository;
import org.apache.fineract.investor.service.LoanAccountOwnerTransferService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@Conditional(InvestorModuleIsEnabledCondition.class)
public class LoanAccountOwnerTransferBusinessStep implements LoanCOBBusinessStep {

    public static final LocalDate FUTURE_DATE_9999_12_31 = LocalDate.of(9999, 12, 31);
    private final ExternalAssetOwnerTransferRepository externalAssetOwnerTransferRepository;
    private final LoanAccountOwnerTransferService loanAccountOwnerTransferService;

    @Override
    public Loan execute(Loan loan) {
        Long loanId = loan.getId();
        log.debug("start processing loan ownership transfer business step for loan with Id [{}]", loanId);

        LocalDate settlementDate = DateUtils.getBusinessLocalDate();
        List<ExternalAssetOwnerTransfer> transferDataList = externalAssetOwnerTransferRepository.findAll(
                (root, query, criteriaBuilder) -> criteriaBuilder.and(criteriaBuilder.equal(root.get("loanId"), loanId),
                        criteriaBuilder.equal(root.get("settlementDate"), settlementDate),
                        root.get("status").in(List.of(ExternalTransferStatus.PENDING, ExternalTransferStatus.BUYBACK)),
                        criteriaBuilder.greaterThanOrEqualTo(root.get("effectiveDateTo"), FUTURE_DATE_9999_12_31)),
                Sort.by(Sort.Direction.ASC, "id"));
        int size = transferDataList.size();

        if (size == 2) {
            ExternalTransferStatus firstTransferStatus = transferDataList.get(0).getStatus();
            ExternalTransferStatus secondTransferStatus = transferDataList.get(1).getStatus();

            if (!ExternalTransferStatus.PENDING.equals(firstTransferStatus)
                    || !ExternalTransferStatus.BUYBACK.equals(secondTransferStatus)) {
                throw new IllegalStateException(String.format("Illegal transfer found. Expected %s and %s, found: %s and %s",
                        ExternalTransferStatus.PENDING, ExternalTransferStatus.BUYBACK, firstTransferStatus, secondTransferStatus));
            }
            loanAccountOwnerTransferService.handleSameDaySaleAndBuyback(settlementDate, transferDataList, loan);
        } else if (size == 1) {
            ExternalAssetOwnerTransfer transfer = transferDataList.get(0);
            if (ExternalTransferStatus.PENDING.equals(transfer.getStatus())) {
                loanAccountOwnerTransferService.handleSale(loan, settlementDate, transfer);
            } else if (ExternalTransferStatus.BUYBACK.equals(transfer.getStatus())) {
                loanAccountOwnerTransferService.handleBuyback(loan, settlementDate, transfer);
            }
        }

        log.debug("end processing loan ownership transfer business step for loan Id [{}]", loan.getId());
        return loan;
    }

    @Override
    public String getEnumStyledName() {
        return "EXTERNAL_ASSET_OWNER_TRANSFER";
    }

    @Override
    public String getHumanReadableName() {
        return "Execute external asset owner transfer";
    }
}
