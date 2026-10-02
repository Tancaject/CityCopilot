export function localBookingInput(instant: string | number, timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(instant));
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)!.value;
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
}

/** Native datetime-local has no timezone; resolve it against the resource's actual timezone. */
export function bookingInstant(local: string, timezone: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(local)) throw new Error('请选择完整日期和时间');
  const target = Date.parse(`${local}:00Z`);
  if (!Number.isFinite(target)) throw new Error('日期或时间无效');
  let candidate = target;
  for (let i = 0; i < 3; i++) {
    const observed = Date.parse(`${localBookingInput(candidate, timezone)}:00Z`);
    candidate += target - observed;
  }
  if (localBookingInput(candidate, timezone) !== local) throw new Error('此当地时间不存在，请检查日期或夏令时切换');
  return new Date(candidate).toISOString();
}

export function nextBookingDate(date: string, days = 1): string {
  const value = new Date(`${date}T12:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}
export function bookingDayRange(date: string, timezone: string): [string, string] {
  return [bookingInstant(`${date}T00:00`, timezone), bookingInstant(`${nextBookingDate(date)}T00:00`, timezone)];
}
export function bookingTime(instant: string, timezone?: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: timezone, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(instant));
}
export const bookingStatus: Record<string, string> = {
  HELD: '名额已暂留', CONFIRMED: '已确认', CANCELLED: '已取消', HOLD_EXPIRED: '暂留已到期', USED: '已到店核销', NO_SHOW: '未到店',
};
export function bookingPrice(snapshot: Record<string, unknown>): string {
  return snapshot?.pricingMode === 'FIXED' && typeof snapshot.totalCents === 'number'
    ? `¥${(snapshot.totalCents / 100).toFixed(2)}，按预约约定线下结算`
    : '消费金额到店确定';
}
export function bookingMoneyCents(value: string): number {
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) throw new Error('金额必须为非负数，最多保留两位小数');
  const cents = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'));
  if (!Number.isSafeInteger(cents)) throw new Error('金额超出支持范围');
  return cents;
}
