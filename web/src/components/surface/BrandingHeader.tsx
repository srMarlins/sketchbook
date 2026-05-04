export interface BrandingHeaderProps {
  className?: string;
}

export function BrandingHeader({ className }: BrandingHeaderProps) {
  return (
    <div role="img" aria-label="Sketchbook" className={className}>
      <img
        src="/brand/sketchbook.png"
        alt=""
        draggable={false}
        className="h-10 select-none"
      />
    </div>
  );
}
