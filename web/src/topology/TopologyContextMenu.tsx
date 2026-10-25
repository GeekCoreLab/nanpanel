import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import './topology-context-menu.css';

type MenuContextValue = { close: () => void };
const MenuContext = createContext<MenuContextValue | null>(null);

export function TopologyContextMenu({
  x,
  y,
  onClose,
  ariaLabel = '拓扑右键菜单',
  children,
}: {
  x: number;
  y: number;
  onClose: () => void;
  ariaLabel?: string;
  children: ReactNode;
}) {
  const menuRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ x, y });

  useLayoutEffect(() => {
    const bounds = menuRef.current?.getBoundingClientRect();
    if (!bounds) return;
    const next = {
      x: Math.max(8, Math.min(x, window.innerWidth - bounds.width - 8)),
      y: Math.max(8, Math.min(y, window.innerHeight - bounds.height - 8)),
    };
    setPosition(current => current.x === next.x && current.y === next.y ? current : next);
  }, [x, y, children]);

  useEffect(() => {
    const dismiss = (event: globalThis.PointerEvent) => {
      if (!menuRef.current?.contains(event.target as globalThis.Node)) onClose();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', dismiss);
      document.removeEventListener('keydown', escape);
    };
  }, [onClose]);

  useEffect(() => {
    menuRef.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
  }, []);

  return <MenuContext.Provider value={{ close: onClose }}>
    <div className="topology-context-menu" role="menu" aria-label={ariaLabel} ref={menuRef} style={{ left: position.x, top: position.y }}>
      {children}
      <TopologyContextMenuItem className="menu-close" onClick={onClose}>关闭菜单 · Esc</TopologyContextMenuItem>
    </div>
  </MenuContext.Provider>;
}

export function TopologyContextMenuHeading({ children }: { children: ReactNode }) {
  return <strong className="topology-context-menu-heading">{children}</strong>;
}

export function TopologyContextMenuGroup({ children }: { children: ReactNode }) {
  return <div className="topology-context-menu-group">{children}</div>;
}

type MenuItemProps = ButtonHTMLAttributes<HTMLButtonElement> & { closeOnSelect?: boolean };

export function TopologyContextMenuItem({
  children,
  className = '',
  closeOnSelect = true,
  onClick,
  ...buttonProps
}: MenuItemProps) {
  const menu = useContext(MenuContext);
  return <button
    {...buttonProps}
    type="button"
    role="menuitem"
    className={`topology-context-menu-item ${className}`.trim()}
    onClick={event => {
      onClick?.(event);
      if (!event.defaultPrevented && closeOnSelect) menu?.close();
    }}
  >{children}</button>;
}
