package li.cil.oc.util

import li.cil.oc.api.internal.MultiTank
import li.cil.oc.api.machine.Arguments
import net.minecraft.inventory.IInventory
import net.minecraftforge.common.util.ForgeDirection

import scala.language.implicitConversions

object ExtendedArguments {

  implicit def extendedArguments(args: Arguments): ExtendedArguments = new ExtendedArguments(args)

  class ExtendedArguments(val args: Arguments) {
    def optionalItemCount(n: Int) =
      if (args.count > n && args.checkAny(n) != null) {
        math.max(0, math.min(64, args.checkInteger(n)))
      }
      else 64

    def optionalFluidCount(n: Int) =
      if (args.count > n && args.checkAny(n) != null) {
        math.max(0, args.checkInteger(n))
      }
      else 1000

    def checkSlot(inventory: IInventory, n: Int) = {
      val slot = args.checkInteger(n) - 1
      if (slot < 0 || slot >= inventory.getSizeInventory) {
        throw new IllegalArgumentException("invalid slot")
      }
      slot
    }

    def optSlot(inventory: IInventory, n: Int, default: Int) = {
      if (n >= 0 && n < args.count()) checkSlot(inventory, n)
      else default
    }

    def checkTank(multi: MultiTank, n: Int) = {
      val tank = args.checkInteger(n) - 1
      if (tank < 0 || tank >= multi.tankCount) {
        throw new IllegalArgumentException("invalid tank index")
      }
      tank
    }

    def checkSideForAction(n: Int) = checkSide(n, ForgeDirection.SOUTH, ForgeDirection.UP, ForgeDirection.DOWN)

    def checkSideForMovement(n: Int) = checkSide(n, ForgeDirection.SOUTH, ForgeDirection.NORTH, ForgeDirection.UP, ForgeDirection.DOWN)

    def checkSideForFace(n: Int, facing: ForgeDirection) = checkSide(n, ForgeDirection.VALID_DIRECTIONS.filter(_ != facing.getOpposite): _*)

    def checkSide(n: Int, allowed: ForgeDirection*) = {
      val side = args.checkInteger(n)
      if (side < 0 || side > 5) {
        throw new IllegalArgumentException("invalid side")
      }
      val direction = ForgeDirection.getOrientation(side)
      if (allowed.isEmpty || (allowed contains direction)) direction
      else throw new IllegalArgumentException("unsupported side")
    }
  }

}
