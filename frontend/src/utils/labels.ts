export function getPaginationLabel(
  loading: boolean,
  hasMore: boolean,
  exhaustedLabel = '没有更多了',
): string {
  if (loading) {
    return '加载中';
  }
  return hasMore ? '加载更多' : exhaustedLabel;
}
