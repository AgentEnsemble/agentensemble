import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import App from './App.js';
import LivePage from './pages/LivePage.js';
import NetworkPage from './pages/NetworkPage.js';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        {/* Live execution dashboard -- connects to a running ensemble via WebSocket */}
        <Route path="/live" element={<LivePage />} />
        {/* Multi-ensemble network dashboard -- connects to multiple ensembles */}
        <Route path="/network" element={<NetworkPage />} />
        {/* Historical trace viewer -- file upload and CLI-server file listing */}
        <Route path="/*" element={<App />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
