import { useState } from 'react';
import type { ColonyProject, Planet } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { useT } from '../../i18n';

/**
 * Что заложить выделенным колониям — п. 10, список колоний.
 *
 * В окне только то, что по силам <b>каждой</b> колонии набора: список приходит с сервера
 * у каждой колонии свой (`colony.available`), а здесь берётся их пересечение. Иначе
 * приказ падал бы отказом уже после нажатия — у одной колонии здание построено, у другой
 * не изучена технология, — и разбираться, у какой именно, пришлось бы игроку.
 *
 * Пересечение считается по коду проекта, а не по названию: название одно, а код и есть то,
 * что уходит на сервер.
 */
export function ColoniesQueueDialog({
  planets,
  saving,
  error,
  onEnqueue,
  onClose,
}: {
  /** Выделенные колонии: у каждой свой список доступного, и решает их пересечение. */
  planets: Planet[];
  saving: boolean;
  error: string | null;
  onEnqueue: (project: ColonyProject) => void;
  onClose: () => void;
}) {
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const { t } = useT();

  useModalEscape(true, onClose);

  const common = commonProjects(planets);
  const selected = common.find((project) => project.code === selectedCode) ?? common[0] ?? null;

  return (
    <div className="absolute inset-0 z-50 flex items-center justify-center bg-space-950/95 p-8">
      <div className="panel flex max-h-full w-full max-w-3xl flex-col overflow-auto">
        <div className="mb-3 flex items-baseline justify-between gap-6 border-b border-space-700 pb-2">
          <h2 className="text-22 uppercase tracking-[0.3em] text-ink-bright">
            {t('queueAll.title')}
          </h2>
          <span className="text-19 text-ink-faint">
            {t('queueAll.count')} <span className="text-ink">{planets.length}</span>
          </span>
        </div>

        {common.length === 0 ? (
          <p className="py-4 text-20 text-ink-dim">
            {t('queueAll.nothingCommon')}
          </p>
        ) : (
          <div className="grid min-h-0 gap-4 md:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
            <ul className="max-h-[50vh] overflow-auto border border-space-700 bg-space-950/60 p-2">
              {common.map((project) => (
                <li key={project.code}>
                  <button
                    type="button"
                    className={
                      'link block w-full px-1 py-0.5 text-left text-20 '
                      + (project.code === selected?.code ? 'link-active bg-space-800' : '')
                    }
                    onClick={() => setSelectedCode(project.code)}
                    onDoubleClick={() => onEnqueue(project)}
                  >
                    {project.name}
                  </button>
                </li>
              ))}
            </ul>

            <div className="flex flex-col gap-3">
              <div className="border border-space-700 bg-space-950/60 px-3 py-2">
                <p className="text-22 text-ink-bright">{selected?.name}</p>
                <p className="mt-1 text-19 text-ink-faint">
                  {selected?.cost === undefined
                    ? t('queueAll.endless')
                    : t('queueAll.cost', { n: selected.cost })}
                  {selected?.upkeep ? t('queueAll.upkeep', { n: selected.upkeep }) : ''}
                </p>
              </div>
              <p className="border border-space-700 bg-space-950/60 px-3 py-2 text-19 leading-relaxed text-ink-soft">
                {selected?.description}
              </p>
              {/*
                Главное про приказ: он не отменяет того, что колония строит, а встаёт
                первым в очередь. Колония, занятая бесконечной стройкой (дома, товары),
                берётся за новое сразу — за домами очередь ждала бы вечно.
              */}
              <p className="text-18 leading-relaxed text-ink-faint">
                {t('queueAll.note.1')} <span className="text-ink-soft">{t('queueAll.note.first')}</span>{' '}
                {t('queueAll.note.2')}
              </p>
            </div>
          </div>
        )}

        {error ? <p className="mt-3 text-19 text-danger">{error}</p> : null}

        <div className="mt-4 flex items-center justify-end gap-5 border-t border-space-700 pt-3 text-20">
          <button type="button" className="link" onClick={onClose}>
            {t('common.cancelEsc')}
          </button>
          <button
            type="button"
            className="link disabled:cursor-not-allowed disabled:text-ink-off"
            disabled={saving || selected === null}
            onClick={() => selected && onEnqueue(selected)}
          >
            {t('queueAll.submit')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Проекты, доступные каждой из колоний набора: пересечение их списков по коду. */
const commonProjects = (planets: Planet[]): ColonyProject[] => {
  const lists = planets.map((planet) => planet.colony?.available ?? []);
  if (lists.length === 0) {
    return [];
  }
  return lists[0].filter((project) =>
    lists.every((list) => list.some((other) => other.code === project.code)),
  );
};
