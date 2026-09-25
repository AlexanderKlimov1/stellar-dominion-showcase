import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { restoreTextScale } from './ui/textScale';
import './index.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Не найден корневой элемент #root');
}

/*
  Размер текста ставится ДО первой отрисовки — п. 11.1: иначе игрок с крупным шрифтом
  видел бы кадр мелкого, а панели дёргались бы на первом же кадре. Запись поверх этого
  скажет своё слово при входе (`applyAccountTextScale`), но она приходит позже, а первый
  экран рисуется уже сейчас.
*/
restoreTextScale();

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
