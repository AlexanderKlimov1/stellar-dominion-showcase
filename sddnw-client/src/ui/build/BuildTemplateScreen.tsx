import { useEffect, useMemo, useState } from 'react';
import { gameApi, GameApiError } from '../../api/client';
import type { BuildingRef, BuildTemplate } from '../../api/types';
import { useModalEscape } from '../useModalEscape';
import { t, useT } from '../../i18n';
import { Figure } from '../Figure';
import { ACCENTS, figureLabelClass } from '../accent';

/**
 * Шаблоны стройки — п. 10, миграция 079: порядок развития, который закладывают колониям
 * одним движением.
 *
 * <b>Зачем.</b> Очередь держит семь мест и заводится каждой колонии отдельно; к середине
 * партии колоний два десятка, и один и тот же порядок — завод, лаборатория, космопорт —
 * игрок набирает руками снова и снова. Список колоний умеет заложить ОДИН проект многим
 * колониям, а нужен обратный ход: набор проектов одной колонии.
 *
 * <b>Экран один на два входа</b> — из главного меню и из окна стройки, — потому что шаблон
 * принадлежит УЧЁТНОЙ ЗАПИСИ, а не партии: порядок развития переживает партию и годится
 * для следующей. Ровно так же устроен экран настроек (`ui/preferences`).
 *
 * <b>Стадию развития назначает игрок</b> (1, 2, 3…), и игра её не толкует: это ярлык,
 * отвечающий на вопрос «а этот шаблон для чего» — ранняя колония, зрелая, столица. По
 * нему шаблоны и разложены в списке. Толковать её сервером значило бы выдумать правило,
 * которого игрок не просил.
 *
 * <b>Список построек берётся из справочника зданий</b>, а не из колонии: шаблон пишут и
 * до партии, где никакой колонии нет вовсе. Поэтому сбыточность шаблона здесь не
 * проверяется — несбыточное отсеется при закладке, и это сказано на самом экране.
 */
export function BuildTemplateScreen({ onClose }: { onClose: () => void }) {
  useT();
  useModalEscape(true, onClose);
  const [buildings, setBuildings] = useState<BuildingRef[]>([]);

  const [templates, setTemplates] = useState<BuildTemplate[] | null>(null);
  const [editing, setEditing] = useState<BuildTemplate | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    // Справочник зданий — своим запросом: экран открывают и до партии, где никакой
    // колонии со списком стройки нет вовсе.
    gameApi.reference.buildings().then(setBuildings).catch(() => setBuildings([]));
    gameApi
      .listBuildTemplates()
      .then(setTemplates)
      .catch((cause: unknown) => {
        setTemplates([]);
        setError(cause instanceof GameApiError ? cause.message : t('template.loadFailed'));
      });
  }, []);

  /** Что можно поставить в шаблон: здания справочника по названию. */
  const catalogue = useMemo(
    () => [...buildings].sort((a, b) => a.name.localeCompare(b.name)),
    [buildings],
  );

  const blank = (): BuildTemplate => ({ id: '', name: t('template.new.name'), tier: 1, projects: [] });

  const save = () => {
    if (!editing) {
      return;
    }
    setSaving(true);
    setError(null);
    gameApi
      .saveBuildTemplate({
        id: editing.id || undefined,
        name: editing.name.trim() || t('template.new.name'),
        tier: editing.tier,
        projects: editing.projects,
      })
      .then((saved) => {
        setTemplates((current) => {
          const rest = (current ?? []).filter((one) => one.id !== saved.id);
          return [...rest, saved].sort((a, b) => a.tier - b.tier || a.name.localeCompare(b.name));
        });
        setEditing(null);
      })
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('template.saveFailed')))
      .finally(() => setSaving(false));
  };

  const remove = (template: BuildTemplate) => {
    setSaving(true);
    gameApi
      .deleteBuildTemplate(template.id)
      .then(() => {
        setTemplates((current) => (current ?? []).filter((one) => one.id !== template.id));
        setEditing((current) => (current?.id === template.id ? null : current));
      })
      .catch((cause: unknown) =>
        setError(cause instanceof GameApiError ? cause.message : t('template.deleteFailed')))
      .finally(() => setSaving(false));
  };

  /** Правка порядка: проект переставляется на соседнее место, как и в самой очереди. */
  const move = (index: number, to: number) => {
    setEditing((current) => {
      if (!current || to < 0 || to >= current.projects.length) {
        return current;
      }
      const projects = [...current.projects];
      const [moved] = projects.splice(index, 1);
      projects.splice(to, 0, moved);
      return { ...current, projects };
    });
  };

  return (
    <div className="absolute inset-0 z-50 flex flex-col gap-3 overflow-y-auto bg-space-950 p-4">
      <div className="flex items-baseline justify-between border-b border-space-700 pb-2">
        <h2 className="panel-title text-22">{t('template.title')}</h2>
        <button type="button" className="link text-20" onClick={onClose}>
          {t('common.close')} (Esc)
        </button>
      </div>

      <p className="text-19 leading-relaxed text-ink-faint">{t('template.hint')}</p>
      {error ? <p className="text-19 text-danger">{error}</p> : null}

      <div className="grid min-h-0 flex-1 grid-cols-[18rem_minmax(0,1fr)] gap-3">
        {/* Слева — сами шаблоны, разложенные по стадии развития. */}
        <section className="panel flex min-h-0 flex-col overflow-y-auto">
          <div className="panel-title">{t('template.list')}</div>
          {templates === null ? <p className="text-19 text-ink-dim">{t('load.loading')}</p> : null}
          {templates !== null && templates.length === 0 ? (
            <p className="text-19 text-ink-faint">{t('template.empty')}</p>
          ) : null}
          <ul className="mt-1 flex flex-col gap-1">
            {(templates ?? []).map((template) => (
              <li key={template.id} className="flex items-baseline justify-between gap-2">
                <button
                  type="button"
                  className={'link min-w-0 flex-1 truncate text-left ' + ACCENTS.value.type + ' '
                    + (editing?.id === template.id ? 'link-active' : '')}
                  onClick={() => setEditing({ ...template })}
                >
                  {t('template.tier.short', { n: template.tier })} {template.name}
                  <span className={figureLabelClass('value')}>{' · '}</span><Figure accent="value">{template.projects.length}</Figure>
                </button>
                <button type="button" className="link text-18 text-ink-faint" disabled={saving}
                        title={t('template.delete')} onClick={() => remove(template)}>
                  ✕
                </button>
              </li>
            ))}
          </ul>
          <button type="button" className="link mt-3 text-left text-20 text-accent"
                  onClick={() => setEditing(blank())}>
            ▸ {t('template.new')}
          </button>
        </section>

        {/* Справа — правка выбранного: имя, стадия и сам порядок стройки. */}
        <section className="panel flex min-h-0 flex-col">
          {editing === null ? (
            <p className="text-19 text-ink-faint">{t('template.pick')}</p>
          ) : (
            <>
              <div className="flex flex-wrap items-baseline gap-3">
                <label className="text-19 text-ink-dim">
                  {t('template.name')}{' '}
                  <input
                    className="field w-56 text-20"
                    value={editing.name}
                    maxLength={80}
                    onChange={(event) => setEditing({ ...editing, name: event.target.value })}
                  />
                </label>
                <label className="text-19 text-ink-dim">
                  {t('template.tier')}{' '}
                  <select
                    className="field w-24 text-20"
                    value={editing.tier}
                    onChange={(event) => setEditing({ ...editing, tier: Number(event.target.value) })}
                  >
                    {[1, 2, 3, 4, 5].map((tier) => (
                      <option key={tier} value={tier}>{t('template.tier.short', { n: tier })}</option>
                    ))}
                  </select>
                </label>
              </div>

              <div className="mt-3 grid min-h-0 flex-1 grid-cols-2 gap-3">
                <div className="flex min-h-0 flex-col">
                  <div className="panel-title">{t('template.order')}</div>
                  {editing.projects.length === 0 ? (
                    <p className="text-19 text-ink-faint">{t('template.order.empty')}</p>
                  ) : null}
                  <ol className="mt-1 flex flex-col gap-0.5 overflow-y-auto">
                    {editing.projects.map((code, index) => (
                      <li key={`${code}-${index}`} className="flex items-baseline gap-2">
                        <span className="w-4 shrink-0 text-right text-18 text-ink-faint">{index + 1}</span>
                        <span className="min-w-0 flex-1 truncate text-20 text-ink">
                          {catalogue.find((one) => one.code === code)?.name ?? code}
                        </span>
                        <span className="flex shrink-0 gap-1 text-18">
                          <button type="button" className="link" title={t('build.up.title')}
                                  onClick={() => move(index, index - 1)}>▲</button>
                          <button type="button" className="link" title={t('build.down.title')}
                                  onClick={() => move(index, index + 1)}>▼</button>
                          <button type="button" className="link" title={t('template.order.remove')}
                                  onClick={() => setEditing({
                                    ...editing,
                                    projects: editing.projects.filter((_, at) => at !== index),
                                  })}>✕</button>
                        </span>
                      </li>
                    ))}
                  </ol>
                </div>

                <div className="flex min-h-0 flex-col">
                  <div className="panel-title">{t('template.catalogue')}</div>
                  <ul className="mt-1 flex flex-col overflow-y-auto">
                    {catalogue.map((building) => (
                      <li key={building.code}>
                        <button
                          type="button"
                          className="link block w-full truncate px-1 py-0.5 text-left text-20"
                          title={building.description}
                          onClick={() => setEditing({
                            ...editing,
                            projects: [...editing.projects, building.code],
                          })}
                        >
                          + {building.name}
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>

              <div className="mt-3 flex items-baseline justify-end gap-4 border-t border-space-700 pt-2">
                <button type="button" className="link text-20" onClick={() => setEditing(null)}>
                  {t('common.cancel')}
                </button>
                <button type="button" className="link text-20 text-accent" disabled={saving}
                        onClick={save}>
                  {t('template.save')}
                </button>
              </div>
            </>
          )}
        </section>
      </div>
    </div>
  );
}
