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
package org.apache.fineract.investor.service;

import static org.apache.fineract.investor.data.ExternalTransferStatus.DECLINED;
import static org.apache.fineract.investor.data.ExternalTransferSubStatus.BALANCE_NEGATIVE;
import static org.apache.fineract.investor.data.ExternalTransferSubStatus.BALANCE_ZERO;
import static org.apache.fineract.investor.data.ExternalTransferSubStatus.SAMEDAY_TRANSFERS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanAccountSnapshotBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.interoperation.util.MathUtil;
import org.apache.fineract.investor.data.ExternalTransferStatus;
import org.apache.fineract.investor.data.ExternalTransferSubStatus;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransfer;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransferDetails;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransferLoanMapping;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransferLoanMappingRepository;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransferRepository;
import org.apache.fineract.investor.domain.LoanOwnershipTransferBusinessEvent;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoanAccountOwnerTransferServiceImpl implements LoanAccountOwnerTransferService {

    public static final LocalDate FUTURE_DATE_9999_12_31 = LocalDate.of(9999, 12, 31);
    private final ExternalAssetOwnerTransferRepository externalAssetOwnerTransferRepository;
    private final ExternalAssetOwnerTransferLoanMappingRepository externalAssetOwnerTransferLoanMappingRepository;
    private final AccountingService accountingService;
    private final BusinessEventNotifierService businessEventNotifierService;

    @Override
    public void handleBuyback(final Loan loan, final LocalDate settlementDate,
            final ExternalAssetOwnerTransfer buybackExternalAssetOwnerTransfer) {
        ExternalAssetOwnerTransfer activeExternalAssetOwnerTransfer = getActiveExternalAssetOwnerTransfer(loan,
                buybackExternalAssetOwnerTransfer);
        ExternalAssetOwnerTransfer newExternalAssetOwnerTransfer = buybackAsset(loan, settlementDate, buybackExternalAssetOwnerTransfer,
                activeExternalAssetOwnerTransfer);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(newExternalAssetOwnerTransfer, loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanAccountSnapshotBusinessEvent(loan));
    }

    private ExternalAssetOwnerTransfer getActiveExternalAssetOwnerTransfer(Loan loan,
            ExternalAssetOwnerTransfer buybackExternalAssetOwnerTransfer) {
        return externalAssetOwnerTransferRepository
                .findOne((root, query, criteriaBuilder) -> criteriaBuilder.and(criteriaBuilder.equal(root.get("loanId"), loan.getId()),
                        criteriaBuilder.equal(root.get("owner"), buybackExternalAssetOwnerTransfer.getOwner()),
                        criteriaBuilder.equal(root.get("status"), ExternalTransferStatus.ACTIVE),
                        criteriaBuilder.equal(root.get("effectiveDateTo"), FUTURE_DATE_9999_12_31)))
                .orElseThrow();
    }

    private ExternalAssetOwnerTransfer buybackAsset(final Loan loan, final LocalDate settlementDate,
            ExternalAssetOwnerTransfer buybackExternalAssetOwnerTransfer, ExternalAssetOwnerTransfer activeExternalAssetOwnerTransfer) {
        activeExternalAssetOwnerTransfer.setEffectiveDateTo(settlementDate);
        buybackExternalAssetOwnerTransfer.setEffectiveDateTo(settlementDate);
        buybackExternalAssetOwnerTransfer
                .setExternalAssetOwnerTransferDetails(createAssetOwnerTransferDetails(loan, buybackExternalAssetOwnerTransfer));
        externalAssetOwnerTransferRepository.save(activeExternalAssetOwnerTransfer);
        buybackExternalAssetOwnerTransfer = externalAssetOwnerTransferRepository.save(buybackExternalAssetOwnerTransfer);
        externalAssetOwnerTransferLoanMappingRepository.deleteByLoanIdAndOwnerTransfer(loan.getId(), activeExternalAssetOwnerTransfer);
        accountingService.createJournalEntriesForBuybackAssetTransfer(loan, buybackExternalAssetOwnerTransfer);
        return buybackExternalAssetOwnerTransfer;
    }

    @Override
    public void handleSale(final Loan loan, final LocalDate settlementDate, final ExternalAssetOwnerTransfer externalAssetOwnerTransfer) {
        ExternalAssetOwnerTransfer newExternalAssetOwnerTransfer = sellAsset(loan, settlementDate, externalAssetOwnerTransfer);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(newExternalAssetOwnerTransfer, loan));
        if (!DECLINED.equals(newExternalAssetOwnerTransfer.getStatus())) {
            businessEventNotifierService.notifyPostBusinessEvent(new LoanAccountSnapshotBusinessEvent(loan));
        }
    }

    private ExternalAssetOwnerTransfer sellAsset(final Loan loan, final LocalDate settlementDate,
            ExternalAssetOwnerTransfer externalAssetOwnerTransfer) {
        ExternalAssetOwnerTransfer newExternalAssetOwnerTransfer;
        if (isTransferable(loan)) {
            newExternalAssetOwnerTransfer = createActiveEntry(settlementDate, externalAssetOwnerTransfer);
            createActiveMapping(loan.getId(), newExternalAssetOwnerTransfer);
            newExternalAssetOwnerTransfer
                    .setExternalAssetOwnerTransferDetails(createAssetOwnerTransferDetails(loan, newExternalAssetOwnerTransfer));
            accountingService.createJournalEntriesForSaleAssetTransfer(loan, newExternalAssetOwnerTransfer);
        } else {
            ExternalTransferSubStatus subStatus = isBiggerThanZero(loan.getTotalOverpaid()) ? BALANCE_NEGATIVE : BALANCE_ZERO;
            newExternalAssetOwnerTransfer = createNewEntry(settlementDate, externalAssetOwnerTransfer, DECLINED, subStatus, settlementDate,
                    settlementDate, Optional.empty());
        }
        return newExternalAssetOwnerTransfer;
    }

    private void createActiveMapping(Long loanId, ExternalAssetOwnerTransfer externalAssetOwnerTransfer) {
        ExternalAssetOwnerTransferLoanMapping externalAssetOwnerTransferLoanMapping = new ExternalAssetOwnerTransferLoanMapping();
        externalAssetOwnerTransferLoanMapping.setLoanId(loanId);
        externalAssetOwnerTransferLoanMapping.setOwnerTransfer(externalAssetOwnerTransfer);
        externalAssetOwnerTransferLoanMappingRepository.save(externalAssetOwnerTransferLoanMapping);
    }

    private boolean isTransferable(final Loan loan) {
        return MathUtil.nullToDefault(loan.getLoanSummary().getTotalOutstanding(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0;
    }

    @Override
    public void handleSameDaySaleAndBuyback(final LocalDate settlementDate, final List<ExternalAssetOwnerTransfer> transferDataList,
            Loan loan) {
        ExternalAssetOwnerTransfer cancelledPendingTransfer = cancelTransfer(settlementDate, transferDataList.get(0));
        ExternalAssetOwnerTransfer cancelledBuybackTransfer = cancelTransfer(settlementDate, transferDataList.get(1));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(cancelledPendingTransfer, loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(cancelledBuybackTransfer, loan));
    }

    private ExternalAssetOwnerTransfer cancelTransfer(final LocalDate settlementDate,
            final ExternalAssetOwnerTransfer externalAssetOwnerTransfer) {
        return createNewEntry(settlementDate, externalAssetOwnerTransfer, ExternalTransferStatus.CANCELLED,
                ExternalTransferSubStatus.SAMEDAY_TRANSFERS, settlementDate, settlementDate, Optional.empty());
    }

    @Override
    public void handleLoanClosedOrOverpaid(Loan loan) {
        Long loanId = loan.getId();
        List<ExternalAssetOwnerTransfer> transferDataList = externalAssetOwnerTransferRepository.findAll(
                (root, query, criteriaBuilder) -> criteriaBuilder.and(criteriaBuilder.equal(root.get("loanId"), loanId),
                        root.get("status").in(List.of(ExternalTransferStatus.PENDING, ExternalTransferStatus.BUYBACK)),
                        criteriaBuilder.greaterThanOrEqualTo(root.get("effectiveDateTo"), FUTURE_DATE_9999_12_31)),
                Sort.by(Sort.Direction.ASC, "id"));
        LocalDate settlementDate = DateUtils.getBusinessLocalDate();

        if (transferDataList.size() == 2) {
            ExternalAssetOwnerTransfer cancelledPendingTransfer;
            ExternalAssetOwnerTransfer cancelledBuybackTransfer;
            if (isSameDayTransfers(transferDataList)) {
                cancelledPendingTransfer = cancelTransfer(settlementDate, transferDataList.get(0), SAMEDAY_TRANSFERS);
                cancelledBuybackTransfer = cancelTransfer(settlementDate, transferDataList.get(1), SAMEDAY_TRANSFERS);
            } else {
                ExternalTransferSubStatus subStatus = isBiggerThanZero(loan.getTotalOverpaid()) ? BALANCE_NEGATIVE : BALANCE_ZERO;
                cancelledPendingTransfer = cancelTransfer(settlementDate, transferDataList.get(0), subStatus);
                cancelledBuybackTransfer = cancelTransfer(settlementDate, transferDataList.get(1), subStatus);
            }
            businessEventNotifierService.notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(cancelledPendingTransfer, loan));
            businessEventNotifierService.notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(cancelledBuybackTransfer, loan));
        } else if (transferDataList.size() == 1) {
            ExternalAssetOwnerTransfer transfer = transferDataList.get(0);
            if (ExternalTransferStatus.PENDING.equals(transfer.getStatus())) {
                ExternalTransferSubStatus subStatus = isBiggerThanZero(loan.getTotalOverpaid()) ? BALANCE_NEGATIVE : BALANCE_ZERO;
                ExternalAssetOwnerTransfer declinedExternalAssetOwnerTransfer = createNewEntry(settlementDate, transfer, DECLINED,
                        subStatus, settlementDate, settlementDate, Optional.of(DateUtils.getBusinessLocalDate()));
                businessEventNotifierService
                        .notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(declinedExternalAssetOwnerTransfer, loan));
            } else if (ExternalTransferStatus.BUYBACK.equals(transfer.getStatus())) {
                handleBuybackBeforeOriginalSettlementDate(loan, settlementDate, transfer);
            }
        }
    }

    private static boolean isSameDayTransfers(List<ExternalAssetOwnerTransfer> transferDataList) {
        return Objects.equals(transferDataList.get(0).getSettlementDate(), transferDataList.get(1).getSettlementDate());
    }

    private void handleBuybackBeforeOriginalSettlementDate(final Loan loan, final LocalDate settlementDate,
            final ExternalAssetOwnerTransfer buybackExternalAssetOwnerTransfer) {
        ExternalAssetOwnerTransfer activeExternalAssetOwnerTransfer = getActiveExternalAssetOwnerTransfer(loan,
                buybackExternalAssetOwnerTransfer);
        ExternalAssetOwnerTransfer newExternalAssetOwnerTransfer = buybackAssetBeforeOriginalSettlementDate(loan, settlementDate,
                buybackExternalAssetOwnerTransfer, activeExternalAssetOwnerTransfer);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanOwnershipTransferBusinessEvent(newExternalAssetOwnerTransfer, loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanAccountSnapshotBusinessEvent(loan));
    }

    private ExternalAssetOwnerTransfer buybackAssetBeforeOriginalSettlementDate(final Loan loan, final LocalDate settlementDate,
            ExternalAssetOwnerTransfer buybackExternalAssetOwnerTransfer, ExternalAssetOwnerTransfer activeExternalAssetOwnerTransfer) {
        activeExternalAssetOwnerTransfer.setEffectiveDateTo(settlementDate);
        buybackExternalAssetOwnerTransfer.setEffectiveDateTo(settlementDate);
        buybackExternalAssetOwnerTransfer.setSettlementDate(settlementDate);
        ExternalTransferSubStatus subStatus = isBiggerThanZero(loan.getTotalOverpaid()) ? BALANCE_NEGATIVE : BALANCE_ZERO;
        buybackExternalAssetOwnerTransfer.setSubStatus(subStatus);
        buybackExternalAssetOwnerTransfer
                .setExternalAssetOwnerTransferDetails(createAssetOwnerTransferDetails(loan, buybackExternalAssetOwnerTransfer));

        externalAssetOwnerTransferRepository.save(activeExternalAssetOwnerTransfer);
        buybackExternalAssetOwnerTransfer = externalAssetOwnerTransferRepository.save(buybackExternalAssetOwnerTransfer);

        externalAssetOwnerTransferLoanMappingRepository.deleteByLoanIdAndOwnerTransfer(loan.getId(), activeExternalAssetOwnerTransfer);
        accountingService.createJournalEntriesForBuybackAssetTransfer(loan, buybackExternalAssetOwnerTransfer);
        return buybackExternalAssetOwnerTransfer;
    }

    private ExternalAssetOwnerTransfer cancelTransfer(final LocalDate settlementDate,
            final ExternalAssetOwnerTransfer externalAssetOwnerTransfer, final ExternalTransferSubStatus subStatus) {
        return createNewEntry(settlementDate, externalAssetOwnerTransfer, ExternalTransferStatus.CANCELLED, subStatus, settlementDate,
                settlementDate, Optional.of(settlementDate));
    }

    private boolean isBiggerThanZero(BigDecimal loanTotalOverpaid) {
        return MathUtil.nullToDefault(loanTotalOverpaid, BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0;
    }

    private ExternalAssetOwnerTransfer createNewEntry(final LocalDate settlementDate,
            final ExternalAssetOwnerTransfer externalAssetOwnerTransfer, final ExternalTransferStatus status,
            final ExternalTransferSubStatus subStatus, final LocalDate effectiveDateFrom, final LocalDate effectiveDateTo,
            final Optional<LocalDate> oldExternalAssetOwnerTransferSettlementDate) {
        ExternalAssetOwnerTransfer newExternalAssetOwnerTransfer = new ExternalAssetOwnerTransfer();
        newExternalAssetOwnerTransfer.setOwner(externalAssetOwnerTransfer.getOwner());
        newExternalAssetOwnerTransfer.setExternalId(externalAssetOwnerTransfer.getExternalId());
        newExternalAssetOwnerTransfer.setStatus(status);
        newExternalAssetOwnerTransfer.setSubStatus(subStatus);
        newExternalAssetOwnerTransfer.setSettlementDate(settlementDate);
        newExternalAssetOwnerTransfer.setLoanId(externalAssetOwnerTransfer.getLoanId());
        newExternalAssetOwnerTransfer.setExternalLoanId(externalAssetOwnerTransfer.getExternalLoanId());
        newExternalAssetOwnerTransfer.setPurchasePriceRatio(externalAssetOwnerTransfer.getPurchasePriceRatio());
        newExternalAssetOwnerTransfer.setEffectiveDateFrom(effectiveDateFrom);
        newExternalAssetOwnerTransfer.setEffectiveDateTo(effectiveDateTo);

        externalAssetOwnerTransfer.setEffectiveDateTo(settlementDate);
        oldExternalAssetOwnerTransferSettlementDate.ifPresent(externalAssetOwnerTransfer::setSettlementDate);
        externalAssetOwnerTransferRepository.save(externalAssetOwnerTransfer);
        return externalAssetOwnerTransferRepository.save(newExternalAssetOwnerTransfer);
    }

    private ExternalAssetOwnerTransfer createActiveEntry(final LocalDate settlementDate,
            final ExternalAssetOwnerTransfer externalAssetOwnerTransfer) {
        LocalDate effectiveFrom = settlementDate.plusDays(1);
        return createNewEntry(settlementDate, externalAssetOwnerTransfer, ExternalTransferStatus.ACTIVE, null, effectiveFrom,
                FUTURE_DATE_9999_12_31, Optional.empty());
    }

    private ExternalAssetOwnerTransferDetails createAssetOwnerTransferDetails(Loan loan,
            ExternalAssetOwnerTransfer externalAssetOwnerTransfer) {
        ExternalAssetOwnerTransferDetails details = new ExternalAssetOwnerTransferDetails();
        details.setExternalAssetOwnerTransfer(externalAssetOwnerTransfer);
        details.setTotalOutstanding(Objects.requireNonNullElse(loan.getLoanSummary().getTotalOutstanding(), BigDecimal.ZERO));
        details.setTotalPrincipalOutstanding(
                Objects.requireNonNullElse(loan.getLoanSummary().getTotalPrincipalOutstanding(), BigDecimal.ZERO));
        details.setTotalInterestOutstanding(
                Objects.requireNonNullElse(loan.getLoanSummary().getTotalInterestOutstanding(), BigDecimal.ZERO));
        details.setTotalFeeChargesOutstanding(
                Objects.requireNonNullElse(loan.getLoanSummary().getTotalFeeChargesOutstanding(), BigDecimal.ZERO));
        details.setTotalPenaltyChargesOutstanding(
                Objects.requireNonNullElse(loan.getLoanSummary().getTotalPenaltyChargesOutstanding(), BigDecimal.ZERO));
        details.setTotalOverpaid(Objects.requireNonNullElse(loan.getTotalOverpaid(), BigDecimal.ZERO));
        return details;
    }
}
