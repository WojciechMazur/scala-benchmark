// Progress is a lease granted by the supervisor's monotonic clock. Re-reading
// an unchanged file must never renew it. Decimal strings preserve 64-bit values.
const maximum = 9223372036854775807n;
const startup = 30000000000n;
export class ProgressDeadline {
  constructor(timeoutNanos, clock = () => process.hrtime.bigint()) {
    this.clock = clock;
    this.startedAt = clock();
    const timeout = BigInt(timeoutNanos);
    if (timeout < 0n || timeout > maximum) throw new Error('Invalid fork timeout');
    this.budget = timeout > startup ? timeout : startup;
    this.sequence = -1n;
  }
  receive(text) {
    const fields = /^3 ([0-9]+) ([0-9]+)\n$/.exec(text);
    if (!fields) return false;
    const sequence = BigInt(fields[1]);
    const budget = BigInt(fields[2]);
    if (sequence > maximum || sequence <= this.sequence || budget <= 0n || budget > maximum) return false;
    this.sequence = sequence;
    this.budget = budget;
    this.startedAt = this.clock();
    return true;
  }
  expired() { return this.clock() - this.startedAt >= this.budget; }
}
