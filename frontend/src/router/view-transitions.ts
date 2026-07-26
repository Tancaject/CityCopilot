import { nextTick } from 'vue';
import type { RouteLocationRaw, Router } from 'vue-router';

/**
 * Wrap router.push/replace with the View Transitions API so route changes
 * morph instead of hard-cutting. Falls back silently when:
 *   - Browser lacks document.startViewTransition (Firefox < 130, etc.)
 *   - User has prefers-reduced-motion: reduce
 *
 * Back/forward popstate navigations bypass router.push and don't animate,
 * which is acceptable degradation.
 */
export function setupViewTransitions(router: Router) {
  if (typeof document === 'undefined') return;

  type StartFn = (cb: () => unknown) => { finished: Promise<void> } | void;
  const startViewTransition = (document as Document & { startViewTransition?: StartFn }).startViewTransition;
  if (typeof startViewTransition !== 'function') return;

  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

  const wrap = <T>(navigate: (to: RouteLocationRaw) => Promise<T>) => {
    return function (this: Router, to: RouteLocationRaw): Promise<T> {
      if (prefersReducedMotion.matches) {
        return navigate(to);
      }
      return new Promise<T>((resolve, reject) => {
        startViewTransition.call(document, async () => {
          try {
            const result = await navigate(to);
            await nextTick();
            resolve(result);
          } catch (error) {
            reject(error);
          }
        });
      });
    };
  };

  const originalPush = router.push.bind(router);
  const originalReplace = router.replace.bind(router);
  router.push = wrap(originalPush) as Router['push'];
  router.replace = wrap(originalReplace) as Router['replace'];
}
