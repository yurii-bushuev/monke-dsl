package kek.dsl

/** The complete example from PROPOSAL.md §22 — the workbench's default document. */
object Sample:

  val source: String =
    """bounded-context Payments {

    @description("Unique identifier of a payment.")
    value PaymentId = UUID;

    value PartnerId = String;

    value Amount = BigDecimal;

    value Currency;

    @description("Monetary amount in a specific currency.")
    @external-link(
        "domain-docs",
        "https://wiki.example.com/money"
    )
    value Money {
        amount: Amount;
        currency: Currency;
    }

    value PaymentStatus {
        Created
        Withdrawn
        Committed
        Refunded
    }

    value SettlementId = UUID;

    value SettlementStatus {
        Pending
        Settled
    }


    @description("Payment aggregate.")
    @tags("payment", "core")
    entity Payment @aggregate-root {
        id: PaymentId;
        partnerId: PartnerId;
        amount: Money;
        status: PaymentStatus;
    }

    @description("Settlement aggregate; settled by a payment withdrawal reported via integration event.")
    entity Settlement @aggregate-root {
        id: SettlementId;
        paymentId: PaymentId;
        amount: Money;
        status: SettlementStatus;
    }


    command WithdrawPayment {
        paymentId: PaymentId;
    }

    command CommitPayment {
        paymentId: PaymentId;
    }

    command RefundPayment {
        paymentId: PaymentId;
        amount: Money;
    }

    command SettlePayment {
        paymentId: PaymentId;
    }


    event PaymentWithdrawn {
        paymentId: PaymentId;
    }

    event PaymentCommitted {
        paymentId: PaymentId;
    }

    event PaymentRefunded {
        paymentId: PaymentId;
        amount: Money;
    }

    event PaymentSettled {
        paymentId: PaymentId;
    }

    event PaymentRejected {
        paymentId: PaymentId;
    }


    integration-event PaymentWithdrawalCompleted {
        paymentId: PaymentId;
    }

    integration-event PaymentCommitCompleted {
        paymentId: PaymentId;
    }


    error PaymentNotFound;

    error InvalidPaymentState;

    error InsufficientRefundableAmount {
        available: Money;
        requested: Money;
    }

    error SettlementAlreadyExists;


    @description("Bank client initiating payment operations.")
    @tags("external", "client")
    actor BankClient {
        triggers WithdrawPayment
        triggers RefundPayment
    }

    @description("Backend scheduler committing payments in due time.")
    actor PaymentScheduler {
        triggers CommitPayment
    }


    domain-entity PaymentBehavior on Payment {

        handles WithdrawPayment {
            on Payment -> PaymentWithdrawn
            fails PaymentNotFound, InvalidPaymentState
        }

        handles CommitPayment {
            on Payment -> PaymentCommitted
        }

        handles RefundPayment {
            on Payment -> PaymentRefunded
            fails InsufficientRefundableAmount
        }


        transition PaymentWithdrawn {
            from Payment -> Payment
            emits PaymentWithdrawalCompleted
        }

        transition PaymentCommitted {
            from Payment -> Payment
            emits PaymentCommitCompleted
        }

        transition PaymentRefunded {
            from Payment -> Payment
        }
    }

    domain-entity SettlementBehavior on Settlement {

        handles SettlePayment from PaymentWithdrawalCompleted {
            match Settlement {
                case "settlement is open"        -> PaymentSettled
                case "payment amount rejected"   -> PaymentRejected
                case "settlement already exists" -> fails SettlementAlreadyExists
            }
        }

        transition PaymentSettled {
            from Settlement -> Settlement
        }

        transition PaymentRejected {
            from Settlement -> Settlement
        }
    }
}"""
