import { createApp } from 'vue';
import App from './App.vue';
import { router } from './router';
import { setupViewTransitions } from './router/view-transitions';
import './styles.css';
import './styles/tokens.css';
import './styles/consumer-warm.css';
import './styles/motion.css';

setupViewTransitions(router);

createApp(App).use(router).mount('#app');

