package me.luna.trollhack.module.modules.combat

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import kotlinx.coroutines.launch
import me.luna.trollhack.event.SafeClientEvent
import me.luna.trollhack.event.events.EntityEvent
import me.luna.trollhack.event.events.RunGameLoopEvent
import me.luna.trollhack.event.events.TickEvent
import me.luna.trollhack.event.events.WorldEvent
import me.luna.trollhack.event.events.combat.CombatEvent
import me.luna.trollhack.event.events.combat.CrystalSetDeadEvent
import me.luna.trollhack.event.events.player.OnUpdateWalkingPlayerEvent
import me.luna.trollhack.event.events.render.Render2DEvent
import me.luna.trollhack.event.events.render.Render3DEvent
import me.luna.trollhack.event.listener
import me.luna.trollhack.event.safeListener
import me.luna.trollhack.manager.managers.CombatManager
import me.luna.trollhack.manager.managers.HotbarManager.spoofHotbar
import me.luna.trollhack.manager.managers.PlayerPacketManager.sendPlayerPacket
import me.luna.trollhack.module.Category
import me.luna.trollhack.module.Module
import me.luna.trollhack.util.Bind
import me.luna.trollhack.util.EntityUtils.eyePosition
import me.luna.trollhack.util.EntityUtils.spoofUnSneak
import me.luna.trollhack.util.TickTimer
import me.luna.trollhack.util.and
import me.luna.trollhack.util.atValue
import me.luna.trollhack.util.collections.CircularArray
import me.luna.trollhack.util.combat.CalcContext
import me.luna.trollhack.util.combat.CombatUtils.scaledHealth
import me.luna.trollhack.util.combat.CrystalUtils
import me.luna.trollhack.util.extension.rootName
import me.luna.trollhack.util.graphics.ESPRenderer
import me.luna.trollhack.util.graphics.ProjectionUtils
import me.luna.trollhack.util.graphics.color.ColorRGB
import me.luna.trollhack.util.graphics.font.renderer.MainFontRenderer
import me.luna.trollhack.util.graphics.mask.EnumFacingMask
import me.luna.trollhack.util.inventory.InventoryTask
import me.luna.trollhack.util.inventory.executedOrTrue
import me.luna.trollhack.util.inventory.inventoryTask
import me.luna.trollhack.util.inventory.operation.swapWith
import me.luna.trollhack.util.inventory.slot.*
import me.luna.trollhack.util.items.blockBlacklist
import me.luna.trollhack.util.math.RotationUtils.getRotationTo
import me.luna.trollhack.util.math.RotationUtils.yaw
import me.luna.trollhack.util.math.VectorUtils
import me.luna.trollhack.util.math.vector.Vec2f
import me.luna.trollhack.util.math.vector.distanceSqTo
import me.luna.trollhack.util.math.vector.toVec3d
import me.luna.trollhack.util.math.vector.toVec3dCenter
import me.luna.trollhack.util.pause.OffhandPause
import me.luna.trollhack.util.pause.withPause
import me.luna.trollhack.util.text.MessageSendUtils
import me.luna.trollhack.util.threads.BackgroundScope
import me.luna.trollhack.util.threads.defaultScope
import me.luna.trollhack.util.threads.runSafe
import me.luna.trollhack.util.world.*
import net.minecraft.block.Block
import net.minecraft.block.BlockBed
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.network.play.client.CPacketAnimation
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.max

@CombatManager.CombatModule
internal object BedAura : Module(
    name = "BedAura",
    description = "Place bed and kills enemies",
    category = Category.COMBAT,
    modulePriority = 70
) {
    private val page = setting("Page", Page.GENERAL)

    private val handMode by setting("Hand Mode", EnumHand.OFF_HAND, page.atValue(Page.GENERAL))
    private val bedSlot by setting("Bed Slot", 3, 1..9, 1, page.atValue(Page.GENERAL) and { handMode == EnumHand.MAIN_HAND })
    private val strictRotation by setting("Strict Rotation", false, page.atValue(Page.GENERAL))
    private val strictDirection by setting("Strict Direction", false, page.atValue(Page.GENERAL))
    private val newPlacement by setting("1.13 Placement", false, page.atValue(Page.GENERAL))
    private val smartDamage by setting("Smart Damage", true, page.atValue(Page.GENERAL))
    private val damageStep by setting("Damage Step", 2.0f, 0.0f..5.0f, 0.1f, page.atValue(Page.GENERAL) and ::smartDamage)
    private val noSuicide by setting("No Suicide", 8.0f, 0.0f..20.0f, 0.25f, page.atValue(Page.GENERAL))
    private val minDamage by setting("Min Damage", 6.0f, 0.0f..20.0f, 0.25f, page.atValue(Page.GENERAL))
    private val maxSelfDamage by setting("Max Self Damage", 6.0f, 0.0f..20.0f, 0.25f, page.atValue(Page.GENERAL))
    private val damageBalance by setting("Damage Balance", -2.5f, -10.0f..10.0f, 0.25f, page.atValue(Page.GENERAL))
    private val range by setting("Range", 5.4f, 0.0f..6.0f, 0.25f, page.atValue(Page.GENERAL))

    private val updateDelay by setting("Update Delay", 50, 5..250, 1, page.atValue(Page.TIMING))
    private val timingMode by setting("Timing Mode", TimingMode.INSTANT, page.atValue(Page.TIMING))
    private val delay by setting("Delay", 75, 0..1000, 1, page.atValue(Page.TIMING) and { timingMode != TimingMode.SWITCH })
    private val placeDelay by setting("Place Delay", 25, 0..1000, 1, page.atValue(Page.TIMING) and { timingMode == TimingMode.SWITCH })
    private val breakDelay by setting("Break Delay", 50, 0..1000, 1, page.atValue(Page.TIMING) and { timingMode == TimingMode.SWITCH })
    private val slowMode by setting("Slow Mode", true, page.atValue(Page.TIMING))
    private val slowModeDamage by setting("Slow Mode Damage", 4.0f, 0.0f..10.0f, 0.25f, page.atValue(Page.TIMING) and ::slowMode)
    private val slowDelay by setting("Slow Delay", 250, 0..1000, 5, page.atValue(Page.TIMING) and ::slowMode and { timingMode != TimingMode.SWITCH })
    private val slowPlaceDelay by setting("Slow Place Delay", 250, 0..1000, 5, page.atValue(Page.TIMING) and ::slowMode and { timingMode == TimingMode.SWITCH })
    private val slowBreakDelay by setting("Slow Break Delay", 50, 0..1000, 5, page.atValue(Page.TIMING) and ::slowMode and { smartDamage || timingMode == TimingMode.SWITCH })

    private val forcePlaceBind by setting("Force Place Bind", Bind(), {
        if (isEnabled && it) {
            toggleForcePlace = !toggleForcePlace
            MessageSendUtils.sendNoSpamChatMessage("$chatName Force placing" + if (toggleForcePlace) " §aenabled" else " §cdisabled")
        }
    }, page.atValue(Page.FORCE_PLACE))
    private val forcePlaceHealth by setting("Force Place Health", 8.0f, 0.0f..20.0f, 0.5f, page.atValue(Page.FORCE_PLACE))
    private val forcePlaceMinDamage by setting("Force Place Min Damage", 1.5f, 0.0f..10.0f, 0.25f, page.atValue(Page.FORCE_PLACE))
    private val forcePlaceDamageBalance by setting("Force Place Damage Balance", 0.0f, -10.0f..10.0f, 0.25f, page.atValue(Page.FORCE_PLACE))

    private val motionDetect by setting("Motion Detect", true, page.atValue(Page.MOTION_DETECT))
    private val targetMotion by setting("Target Motion", 0.15f, 0.0f..0.3f, 0.01f, page.atValue(Page.MOTION_DETECT) and { motionDetect })
    private val selfMotion by setting("Self Motion", 0.22f, 0.0f..0.3f, 0.01f, page.atValue(Page.MOTION_DETECT) and { motionDetect })
    private val motionMinDamage by setting("Motion Min Damage", 3.0f, 0.0f..20.0f, 0.25f, page.atValue(Page.MOTION_DETECT) and { motionDetect })
    private val motionMaxSelfDamage by setting("Motion Max Self Damage", 8.0f, 0.0f..20.0f, 0.25f, page.atValue(Page.MOTION_DETECT) and { motionDetect })
    private val motionDamageBalance by setting("Motion Damage Balance", -5.0f, -10.0f..10.0f, 0.25f, page.atValue(Page.MOTION_DETECT) and { motionDetect })

    private val renderFoot by setting("Render Foot", true, page.atValue(Page.RENDER))
    private val renderHead by setting("Render Head", true, page.atValue(Page.RENDER))
    private val renderBase by setting("Render Base", false, page.atValue(Page.RENDER))
    private val renderDamage by setting("Render Damage", true, page.atValue(Page.RENDER))
    private val footColor by setting("Foot Color", ColorRGB(255, 160, 255), false, page.atValue(Page.RENDER) and ::renderFoot)
    private val headColor by setting("Head Color", ColorRGB(255, 32, 64), false, page.atValue(Page.RENDER) and ::renderHead)
    private val baseColor by setting("Base Color", ColorRGB(32, 255, 32), false, page.atValue(Page.RENDER) and ::renderBase)

    private enum class Page {
        GENERAL, TIMING, FORCE_PLACE, MOTION_DETECT, RENDER
    }

    private enum class TimingMode {
        INSTANT, SYNC, SWITCH
    }

    private val updateTimer = TickTimer()
    private val timer = TickTimer()
    private val renderer = ESPRenderer().apply { aFilled = 31; aOutline = 233 }

    private var switchPlacing = false
    private var placeInfo: PlaceInfo? = null
    private var selfMoving = false
    private var targetMoving = false
    private var toggleForcePlace = false
    private var shouldForcePlace = false
    private var lastDamage = 0.0f
    private var lastTask: InventoryTask? = null

    private val explosionTimer = TickTimer()
    private val explosionCountArray = CircularArray<Int>(8)
    private var explosionCount = 0

    private var inactiveTicks = 10
    var needOffhandBed = false; private set

    private val bedAABB = AxisAlignedBB(0.0, 0.0, 0.0, 1.0, 0.5625, 1.0)
    private val function: World.(BlockPos, IBlockState) -> FastRayTraceAction = { _, blockState ->
        val block = blockState.block
        if (block == Blocks.AIR || block == Blocks.BED || !CrystalUtils.isResistant(blockState)) {
            FastRayTraceAction.SKIP
        } else {
            FastRayTraceAction.CALC
        }
    }

    override fun isActive(): Boolean {
        return isEnabled && inactiveTicks < 10
    }

    override fun getHudInfo(): String {
        return "%.1f".format(explosionCountArray.average() * 4.0)
    }

    init {
        onDisable {
            reset()
        }

        safeListener<WorldEvent.ClientBlockUpdate> { event ->
            placeInfo?.let {
                if (event.pos == it.basePos) {
                    it.updateBlacklisted(event.newState.block)
                }
            }
        }

        listener<CrystalSetDeadEvent> { event ->
            placeInfo?.let {
                if (it.center.squareDistanceTo(event.x, event.y, event.z) < 0.2) {
                    explosionCount++
                }
            }
        }

        listener<Render3DEvent> {
            renderer.render(false)
        }

        listener<Render2DEvent.Absolute> {
            if (!renderDamage) return@listener
            val placeInfo = placeInfo ?: return@listener

            val pos = ProjectionUtils.toAbsoluteScreenPos(placeInfo.center)
            val halfWidth = MainFontRenderer.getWidth(placeInfo.string, 2.0f) * 0.5f
            val halfHeight = MainFontRenderer.getHeight(2.0f) * 0.5f
            val x = (pos.x + -halfWidth).toFloat()
            val y = (pos.y + -halfHeight).toFloat()

            MainFontRenderer.drawString(placeInfo.string, x, y, scale = 2.0f)
        }

        listener<EntityEvent.UpdateHealth> {
            if (it.entity == CombatManager.target) {
                val diff = it.prevHealth - it.health
                if (diff > 0.0f) {
                    lastDamage += diff
                }
            }
        }

        listener<CombatEvent.UpdateTarget> {
            lastDamage = 0.0f
        }

        safeListener<OnUpdateWalkingPlayerEvent.Pre> {
            placeInfo?.let {
                val rotation = if (strictRotation) {
                    getRotationTo(it.hitVec)
                } else {
                    Vec2f(it.side.yaw, 0.0f)
                }

                sendPlayerPacket {
                    rotate(rotation)
                }
            }
        }

        safeListener<TickEvent.Post> {
            inactiveTicks++
            update()
            placeInfo?.let {
                it.updateBlacklisted(world.getBlock(it.basePos))
            }
            runLoop()
        }

        safeListener<RunGameLoopEvent.Tick> {
            if (explosionTimer.tickAndReset(250L)) {
                val count = explosionCount
                explosionCount = 0
                explosionCountArray.add(count)
            }

            update()
            runLoop()
        }

        BackgroundScope.launchLooping(rootName, 5L) {
            if (isEnabled) {
                runSafe {
                    runLoop()
                }
            }
        }
    }

    private fun SafeClientEvent.runLoop() {
        val placeInfo = placeInfo
        if (placeInfo == null || CombatSetting.pause) {
            needOffhandBed = false
            return
        } else {
            needOffhandBed = handMode == EnumHand.OFF_HAND
        }

        if (handMode == EnumHand.MAIN_HAND) {
            if (!lastTask.executedOrTrue) return
            val hotbarSlot = player.hotbarSlots[bedSlot - 1]
            if (hotbarSlot.stack.item != Items.BED) {
                refillBed(hotbarSlot)
                return
            }
        } else {
            if (player.heldItemOffhand.item != Items.BED) return
        }

        val validDamage = !smartDamage || shouldForcePlace || placeInfo.targetDamage - lastDamage >= damageStep

        when (timingMode) {
            TimingMode.INSTANT -> instantTiming(placeInfo, validDamage)
            TimingMode.SYNC -> syncTiming(placeInfo, validDamage)
            TimingMode.SWITCH -> switchTiming(placeInfo, validDamage)
        }
    }

    private fun SafeClientEvent.refillBed(hotbarSlot: HotbarSlot) {
        (player.storageSlots.firstItem(Items.BED)
            ?: player.craftingSlots.firstItem(Items.BED))?.let {
            lastTask = inventoryTask {
                swapWith(it, hotbarSlot)
            }
        }
    }

    private fun SafeClientEvent.instantTiming(placeInfo: PlaceInfo, validDamage: Boolean) {
        if (validDamage) {
            if (timer.tick(getDelay(placeInfo, delay, slowDelay))) {
                placeBed(placeInfo)
                breakBed(placeInfo)
            }
        } else {
            breakIfPlaced(placeInfo, getDelay(placeInfo, delay, slowDelay))
        }
    }

    private fun SafeClientEvent.syncTiming(placeInfo: PlaceInfo, validDamage: Boolean) {
        if (validDamage) {
            if (timer.tick(getDelay(placeInfo, delay, slowDelay))) {
                if (isBedPlaced(placeInfo)) {
                    breakBed(placeInfo)
                } else {
                    placeBed(placeInfo)
                    breakBed(placeInfo)
                }
            }
        } else {
            breakIfPlaced(placeInfo, getDelay(placeInfo, delay, slowDelay))
        }
    }

    private fun SafeClientEvent.switchTiming(placeInfo: PlaceInfo, validDamage: Boolean) {
        if (validDamage) {
            if (switchPlacing) {
                if (timer.tick(getDelay(placeInfo, placeDelay, slowPlaceDelay))) {
                    breakBed(placeInfo)
                    switchPlacing = !switchPlacing
                }
            } else {
                if (timer.tick(getDelay(placeInfo, breakDelay, slowBreakDelay))) {
                    placeBed(placeInfo)
                    switchPlacing = !switchPlacing
                }
            }
        } else {
            breakIfPlaced(placeInfo, getDelay(placeInfo, breakDelay, slowBreakDelay))
        }
    }

    private fun SafeClientEvent.breakIfPlaced(placeInfo: PlaceInfo, delay: Int) {
        if (timer.tick(delay)) {
            if (isBedPlaced(placeInfo)) {
                breakBed(placeInfo)
            }
        }
    }

    private fun SafeClientEvent.isBedPlaced(placeInfo: PlaceInfo): Boolean {
        return world.getBlock(placeInfo.bedPosFoot) == Blocks.BED
            || world.getBlock(placeInfo.bedPosHead) == Blocks.BED
    }

    private fun SafeClientEvent.breakBed(placeInfo: PlaceInfo) {
        val side = getMiningSide(placeInfo.bedPosFoot) ?: EnumFacing.UP
        val hitVecOffset = getHitVecOffset(side)
        player.isSneaking

        player.spoofUnSneak {
            connection.sendPacket(CPacketPlayerTryUseItemOnBlock(placeInfo.bedPosFoot, side, me.luna.trollhack.module.modules.combat.BedAura.handMode, hitVecOffset.x, hitVecOffset.y, hitVecOffset.z))
        }
        connection.sendPacket(CPacketAnimation(handMode))

        timer.reset()
        inactiveTicks = 0
    }

    private fun SafeClientEvent.placeBed(placeInfo: PlaceInfo) {
        val shouldSneak = !player.isSneaking && placeInfo.blackListed
        if (shouldSneak) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        val placePacket = CPacketPlayerTryUseItemOnBlock(placeInfo.basePos, EnumFacing.UP, handMode, 0.5f, 1.0f, 0.5f)

        if (handMode == EnumHand.MAIN_HAND) {
            spoofHotbar(bedSlot - 1) {
                connection.sendPacket(placePacket)
            }
        } else {
            OffhandPause.withPause(BedAura) {
                connection.sendPacket(CPacketPlayerTryUseItemOnBlock(placeInfo.basePos, EnumFacing.UP, handMode, 0.5f, 1.0f, 0.5f))
            }
        }

        connection.sendPacket(CPacketAnimation(handMode))
        if (shouldSneak) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))

        CombatManager.target?.let { player.setLastAttackedEntity(it) }
        timer.reset()
        inactiveTicks = 0
    }

    private fun getDelay(placeInfo: PlaceInfo, delay: Int, slowDelay: Int): Int {
        return if (slowMode
            && !targetMoving
            && !selfMoving
            && CombatManager.target?.let { it.health > forcePlaceHealth } == true
            && placeInfo.targetDamage < slowModeDamage) {
            slowDelay
        } else {
            delay
        }
    }

    private fun SafeClientEvent.update() {
        if (player.dimension == 0 || !player.allSlots.hasItem(Items.BED)) {
            reset()
        } else if (updateTimer.tickAndReset(updateDelay)) {
            defaultScope.launch {
                val info = calcDamage()
                val list = ArrayList<ESPRenderer.Info>()

                if (info != null) {
                    if (renderBase) list.add(ESPRenderer.Info(info.boxBase, baseColor))
                    if (renderFoot) list.add(ESPRenderer.Info(info.boxFoot, footColor, EnumFacingMask.ALL xor EnumFacingMask.getMaskForSide(info.side)))
                    if (renderHead) list.add(ESPRenderer.Info(info.boxHead, headColor, EnumFacingMask.ALL xor EnumFacingMask.getMaskForSide(info.side.opposite)))
                }

                renderer.replaceAll(list)
                placeInfo = info
            }
        }


        selfMoving = motionDetect && CombatManager.trackerSelf?.let { it.motion.length() > selfMotion } ?: false
        targetMoving = motionDetect && CombatManager.trackerTarget?.let { it.motion.length() > targetMotion } ?: false

        shouldForcePlace = CombatManager.target?.let {
            it.health <= forcePlaceHealth
        } ?: false

        CombatManager.target?.let {
            if (System.currentTimeMillis() - CombatManager.getHurtTime(it) > 500L) {
                lastDamage = 0.0f
            }
        }
    }

    private fun SafeClientEvent.calcDamage(): PlaceInfo? {
        val contextSelf = CombatManager.contextSelf ?: return null
        val contextTarget = CombatManager.contextTarget ?: return null
        val eyePos = player.eyePosition
        val map = Long2ObjectOpenHashMap<Vec2f>()
        val mutableBlockPos = BlockPos.MutableBlockPos()

        return VectorUtils.getBlockPosInSphere(eyePos, range)
            .filter { !strictDirection || eyePos.y > it.y + 1.0 }
            .mapToCalcInfo(eyePos)
            .filterNot { contextTarget.entity.getDistanceSqToCenter(it.bedPosHead) > 100.0 }
            .filter { isValidBasePos(it.basePosFoot) && (newPlacement || isValidBasePos(it.basePosHead)) }
            .filter { isValidBedPos(it) }
            .mapNotNull { checkDamage(map, contextSelf, contextTarget, it, mutableBlockPos) }
            .maxWithOrNull(
                compareBy<DamageInfo> { it.targetDamage }
                    .thenByDescending { eyePos.distanceSqTo(it.basePos) }
            )
            ?.toPlaceInfo()
    }

    private fun Sequence<BlockPos>.mapToCalcInfo(eyePos: Vec3d): Sequence<CalcInfo> {
        return if (strictRotation) {
            map {
                val bedPos = it.up()
                val hitVec = it.toVec3d(0.5, 1.0, 0.5)
                val side = calcDirection(eyePos, hitVec)

                CalcInfo(
                    side,
                    hitVec,
                    it,
                    it.offset(side),
                    bedPos,
                    bedPos.offset(side)
                )
            }
        } else {
            flatMap {
                val hitVec = it.toVec3d(0.5, 1.0, 0.5)

                sequenceOf(
                    newCalcInfo(EnumFacing.NORTH, it, hitVec),
                    newCalcInfo(EnumFacing.SOUTH, it, hitVec),
                    newCalcInfo(EnumFacing.WEST, it, hitVec),
                    newCalcInfo(EnumFacing.EAST, it, hitVec)
                )
            }
        }
    }

    private fun calcDirection(eyePos: Vec3d, hitVec: Vec3d): EnumFacing {
        val x = hitVec.x - eyePos.x
        val z = hitVec.z - eyePos.z

        return EnumFacing.HORIZONTALS.maxByOrNull {
            x * it.directionVec.x + z * it.directionVec.z
        } ?: EnumFacing.NORTH
    }

    private fun newCalcInfo(side: EnumFacing, pos: BlockPos, hitVec: Vec3d): CalcInfo {
        val bedPos = pos.up()

        return CalcInfo(
            side,
            hitVec,
            pos,
            pos.offset(side),
            bedPos,
            bedPos.offset(side)
        )
    }

    private fun SafeClientEvent.isValidBasePos(basePos: BlockPos): Boolean {
        return world.getBlockState(basePos).isSideSolid(world, basePos, EnumFacing.UP)
    }

    private fun SafeClientEvent.isValidBedPos(calcInfo: CalcInfo): Boolean {
        val blockState2 = world.getBlockState(calcInfo.bedPosHead)
        val block1 = world.getBlockState(calcInfo.bedPosFoot).block
        val block2 = blockState2.block

        return (block1 == Blocks.AIR || block1 == Blocks.BED)
            && (block2 == Blocks.AIR || block2 == Blocks.BED
            && blockState2.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD && blockState2.getValue(BlockBed.FACING) == calcInfo.side)
    }

    private fun checkDamage(
        map: Long2ObjectOpenHashMap<Vec2f>,
        contextSelf: CalcContext,
        contextTarget: CalcContext,
        calcInfo: CalcInfo,
        mutableBlockPos: BlockPos.MutableBlockPos
    ): DamageInfo? {
        val scaledHealth = contextSelf.entity.scaledHealth
        val (targetDamage, selfDamage) = map.computeIfAbsent(calcInfo.basePosHead.toLong()) {
            val center = calcInfo.bedPosHead.toVec3dCenter()
            Vec2f(
                contextTarget.calcDamage(center, true, 5.0f, mutableBlockPos, function),
                max(contextSelf.calcDamage(center, false, 5.0f, mutableBlockPos, function),
                    contextSelf.calcDamage(center, true, 5.0f, mutableBlockPos, function))
            )
        }

        val diff = targetDamage - selfDamage
        return if (scaledHealth - selfDamage > noSuicide
            && checkSelfDamage(selfDamage)
            && (checkDamage(targetDamage, diff)
                || checkForcePlaceDamage(targetDamage, diff))) {
            DamageInfo(
                calcInfo.side,
                calcInfo.hitVec,
                calcInfo.basePosFoot,
                calcInfo.bedPosFoot,
                calcInfo.bedPosHead,
                targetDamage,
                selfDamage
            )
        } else {
            null
        }
    }

    private fun checkSelfDamage(selfDamage: Float): Boolean {
        return selfMoving && selfDamage <= motionMaxSelfDamage || selfDamage <= maxSelfDamage
    }

    private fun checkDamage(targetDamage: Float, diff: Float): Boolean {
        return (targetMoving && targetDamage >= motionMinDamage || targetDamage >= minDamage)
            && (targetMoving && diff >= motionDamageBalance || diff >= damageBalance)
    }

    private fun checkForcePlaceDamage(targetDamage: Float, diff: Float): Boolean {
        return (toggleForcePlace || shouldForcePlace) && targetDamage >= forcePlaceMinDamage && diff >= forcePlaceDamageBalance
    }

    private fun DamageInfo.toPlaceInfo(): PlaceInfo {
        val directionVec = side.directionVec
        val boxBase = AxisAlignedBB(
            basePos.x.toDouble(), basePos.y + 0.4375, basePos.z.toDouble(),
            basePos.x + 1.0, basePos.y + 1.0, basePos.z + 1.0,
        ).expand(directionVec.x.toDouble(), directionVec.y.toDouble(), directionVec.z.toDouble())
        val boxFoot = bedAABB.offset(bedPosFoot)
        val boxHead = bedAABB.offset(bedPosHead)

        return PlaceInfo(
            side,
            hitVec,
            basePos,
            bedPosFoot,
            bedPosHead,
            targetDamage,
            selfDamage,
            boxBase,
            boxFoot,
            boxHead,
            boxHead.center,
            "${"%.1f".format(targetDamage)}/${"%.1f".format(selfDamage)}"
        )
    }

    private fun reset() {
        updateTimer.reset(-69420L)
        timer.reset(-69420L)
        renderer.clear()

        switchPlacing = false
        placeInfo = null
        selfMoving = false
        targetMoving = false
        toggleForcePlace = false
        shouldForcePlace = false
        lastDamage = 0.0f
        lastTask = null

        inactiveTicks = 10
        needOffhandBed = false
    }

    private class CalcInfo(
        val side: EnumFacing,
        val hitVec: Vec3d,
        val basePosFoot: BlockPos,
        val basePosHead: BlockPos,
        val bedPosFoot: BlockPos,
        val bedPosHead: BlockPos,
    )

    private class DamageInfo(
        val side: EnumFacing,
        val hitVec: Vec3d,
        val basePos: BlockPos,
        val bedPosFoot: BlockPos,
        val bedPosHead: BlockPos,
        val targetDamage: Float,
        val selfDamage: Float
    )

    private class PlaceInfo(
        val side: EnumFacing,
        val hitVec: Vec3d,
        val basePos: BlockPos,
        val bedPosFoot: BlockPos,
        val bedPosHead: BlockPos,
        val targetDamage: Float,
        val selfDamage: Float,
        val boxBase: AxisAlignedBB,
        val boxFoot: AxisAlignedBB,
        val boxHead: AxisAlignedBB,
        val center: Vec3d,
        val string: String
    ) {
        var blackListed = mc.world?.let { blockBlacklist.contains(it.getBlock(basePos)) } ?: false; private set

        fun updateBlacklisted(block: Block) {
            blackListed = blockBlacklist.contains(block)
        }
    }
}