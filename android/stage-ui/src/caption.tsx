import { AnimatePresence, motion } from "motion/react"

import { ShimmeringText } from "@/components/ui/shimmering-text"
import { WORKING, type StageInfo } from "@/emrys"

const INK2 = "#9E9DA4"
const PORCELAIN = "#F3F2EE"

/**
 * The big stage's one-line caption while Emrys works: a status dot in the mood colour and the activity in Geist,
 * ink2 with a porcelain shimmer, low on the stage. It steps aside on a squeezed stage and while Emrys speaks.
 */
export function Caption({ info, reduced }: { info: StageInfo; reduced: boolean }) {
  const text = WORKING[info.state]
  const shown = text !== undefined && !info.short && !info.talking
  return (
    <div className="absolute inset-x-0 bottom-[max(14px,5%)] flex justify-center px-4" data-emrys="caption-slot">
      <AnimatePresence mode="wait">
        {shown && text && (
          <motion.div
            key={text}
            data-emrys="caption"
            data-caption={text}
            initial={reduced ? { opacity: 0 } : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={reduced ? { opacity: 0 } : { opacity: 0, y: -6 }}
            transition={{ duration: reduced ? 0.15 : 0.3, ease: [0.2, 0, 0, 1] }}
            className="flex items-center gap-2 rounded-full border border-[rgb(243_242_238/0.07)] bg-[rgb(18_18_21/0.72)] px-3.5 py-1.5"
          >
            <span className="size-1.5 shrink-0 rounded-full bg-current" aria-hidden="true" />
            <span role="status" aria-label={`Emrys: ${text}`}>
              <ShimmeringText
                text={text}
                className="font-sans text-[13px] leading-5 font-medium tracking-[-0.005em]"
                color={INK2}
                shimmerColor={PORCELAIN}
                duration={reduced ? 0 : 2.2}
                repeat={!reduced}
                repeatDelay={0.6}
                spread={reduced ? 0 : 2.4}
                startOnView={false}
              />
            </span>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
