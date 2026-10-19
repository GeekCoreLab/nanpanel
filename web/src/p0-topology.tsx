import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import Topology from './Topology';
import '@xyflow/react/dist/style.css';
import './style.css';
import './p0-topology.css';

createRoot(document.getElementById('root')!).render(<StrictMode><div className="p0-topology-shell"><header><strong>NaNPanel · P0 实际路由实验</strong><span>隔离 Podman 网络 · 仅绑定本机</span></header><Topology /></div></StrictMode>);
