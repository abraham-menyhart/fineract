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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.investor.data.ExternalTransferStatus;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransfer;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransferLoanMappingRepository;
import org.apache.fineract.investor.domain.ExternalAssetOwnerTransferRepository;
import org.apache.fineract.investor.service.LoanAccountOwnerTransferService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
public class LoanAccountOwnerTransferBusinessStepTest {

    private final LocalDate actualDate = LocalDate.now(ZoneId.systemDefault());
    @Mock
    private ExternalAssetOwnerTransferRepository externalAssetOwnerTransferRepository;
    @Mock
    private ExternalAssetOwnerTransferLoanMappingRepository externalAssetOwnerTransferLoanMappingRepository;

    @Mock
    private LoanAccountOwnerTransferService loanAccountOwnerTransferService;
    private LoanAccountOwnerTransferBusinessStep underTest;

    @BeforeEach
    public void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, actualDate)));
        underTest = new LoanAccountOwnerTransferBusinessStep(externalAssetOwnerTransferRepository, loanAccountOwnerTransferService);
    }

    @Test
    public void givenLoanNoTransfer() {
        // given
        final Loan loanForProcessing = Mockito.mock(Loan.class);
        Long loanId = 1L;
        when(loanForProcessing.getId()).thenReturn(loanId);
        // when
        final Loan processedLoan = underTest.execute(loanForProcessing);
        // then
        verify(externalAssetOwnerTransferRepository, times(1)).findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id")));
        assertEquals(processedLoan, loanForProcessing);
    }

    @Test
    public void givenLoanTwoTransferButInvalidTransfers() {
        // given
        final Loan loanForProcessing = Mockito.mock(Loan.class);
        when(loanForProcessing.getId()).thenReturn(1L);
        ExternalAssetOwnerTransfer firstResponseItem = Mockito.mock(ExternalAssetOwnerTransfer.class);
        ExternalAssetOwnerTransfer secondResponseItem = Mockito.mock(ExternalAssetOwnerTransfer.class);
        when(firstResponseItem.getStatus()).thenReturn(ExternalTransferStatus.PENDING);
        when(secondResponseItem.getStatus()).thenReturn(ExternalTransferStatus.ACTIVE);
        List<ExternalAssetOwnerTransfer> response = List.of(firstResponseItem, secondResponseItem);
        when(externalAssetOwnerTransferRepository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id"))))
                .thenReturn(response);
        // when
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> underTest.execute(loanForProcessing));
        // then
        assertEquals("Illegal transfer found. Expected PENDING and BUYBACK, found: PENDING and ACTIVE", exception.getMessage());
        verify(externalAssetOwnerTransferRepository, times(1)).findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id")));
    }

    @Test
    public void givenLoanTwoTransferSameDay() {
        // given
        final Loan loanForProcessing = Mockito.mock(Loan.class);
        when(loanForProcessing.getId()).thenReturn(1L);
        ExternalAssetOwnerTransfer firstResponseItem = Mockito.mock(ExternalAssetOwnerTransfer.class);
        ExternalAssetOwnerTransfer secondResponseItem = Mockito.mock(ExternalAssetOwnerTransfer.class);

        when(firstResponseItem.getStatus()).thenReturn(ExternalTransferStatus.PENDING);
        when(secondResponseItem.getStatus()).thenReturn(ExternalTransferStatus.BUYBACK);
        List<ExternalAssetOwnerTransfer> response = List.of(firstResponseItem, secondResponseItem);
        when(externalAssetOwnerTransferRepository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id"))))
                .thenReturn(response);

        // when
        final Loan processedLoan = underTest.execute(loanForProcessing);

        // then
        verify(externalAssetOwnerTransferRepository, times(1)).findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id")));
        assertEquals(processedLoan, loanForProcessing);
    }

    @Test
    public void givenLoanBuyback() {
        // given
        final Loan loanForProcessing = Mockito.mock(Loan.class);
        when(loanForProcessing.getId()).thenReturn(1L);
        ExternalAssetOwnerTransfer firstResponseItem = Mockito.mock(ExternalAssetOwnerTransfer.class);
        when(firstResponseItem.getStatus()).thenReturn(ExternalTransferStatus.BUYBACK);
        List<ExternalAssetOwnerTransfer> response = List.of(firstResponseItem);
        when(externalAssetOwnerTransferRepository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id"))))
                .thenReturn(response);
        // when
        final Loan processedLoan = underTest.execute(loanForProcessing);
        // then
        verify(externalAssetOwnerTransferRepository, times(1)).findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id")));
        assertEquals(processedLoan, loanForProcessing);
    }

    @Test
    public void givenLoanSale() {
        // given
        final Loan loanForProcessing = Mockito.mock(Loan.class);
        when(loanForProcessing.getId()).thenReturn(1L);
        ExternalAssetOwnerTransfer firstResponseItem = Mockito.mock(ExternalAssetOwnerTransfer.class);
        when(firstResponseItem.getStatus()).thenReturn(ExternalTransferStatus.PENDING);
        List<ExternalAssetOwnerTransfer> response = List.of(firstResponseItem);
        when(externalAssetOwnerTransferRepository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id"))))
                .thenReturn(response);
        // when
        final Loan processedLoan = underTest.execute(loanForProcessing);
        // then
        verify(externalAssetOwnerTransferRepository, times(1)).findAll(any(Specification.class), eq(Sort.by(Sort.Direction.ASC, "id")));
    }

    @Test
    public void testGetEnumStyledNameSuccessScenario() {
        final String actualEnumName = underTest.getEnumStyledName();
        assertNotNull(actualEnumName);
        assertEquals("EXTERNAL_ASSET_OWNER_TRANSFER", actualEnumName);
    }

    @Test
    public void testGetHumanReadableNameSuccessScenario() {
        final String actualEnumName = underTest.getHumanReadableName();
        assertNotNull(actualEnumName);
        assertEquals("Execute external asset owner transfer", actualEnumName);
    }
}
