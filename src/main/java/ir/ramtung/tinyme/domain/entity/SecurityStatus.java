package ir.ramtung.tinyme.domain.entity;


import lombok.AllArgsConstructor;

import java.util.LinkedList;
import java.util.List;

@AllArgsConstructor
public class SecurityStatus {
    RequestStatus requestStatus;
    LinkedList<Trade> trades;


    public static SecurityStatus accepted(List<Trade> trades) {
        return new SecurityStatus(RequestStatus.ACCEPTED, new LinkedList<>(trades));
    }
    public static SecurityStatus updated(List<Trade> trades) {
        return new SecurityStatus(RequestStatus.UPDATED, new LinkedList<>(trades));
    }
    public static SecurityStatus updated() {
        return new SecurityStatus(RequestStatus.UPDATED, new LinkedList<>());
    }
    public static SecurityStatus acceptedAndActivated(List<Trade> trades) {
        return new SecurityStatus(RequestStatus.ACCEPTED_AND_ACTIVATED, new LinkedList<>(trades));
    }
    public static SecurityStatus updatedAndActivated(List<Trade> trades) {
        return new SecurityStatus(RequestStatus.UPDATED_AND_ACTIVATED, new LinkedList<>(trades));
    }
    public static SecurityStatus notEnoughCredit() {
        return new SecurityStatus(RequestStatus.NOT_ENOUGH_CREDIT, new LinkedList<>());
    }
    public static SecurityStatus notEnoughPositions() {
        return new SecurityStatus(RequestStatus.NOT_ENOUGH_POSITIONS, new LinkedList<>());
    }
    public static SecurityStatus notEnoughInitialTransaction(){
        return new SecurityStatus(RequestStatus.NOT_ENOUGH_INITIAL_TRANSACTION, new LinkedList<>());
    }
    public static SecurityStatus queuedAsInactiveOrder(){
        return new SecurityStatus(RequestStatus.QUEUED_AS_INACTIVE_ORDER, new LinkedList<>());
    }
    public static SecurityStatus auctioned(){
        return new SecurityStatus(RequestStatus.AUCTIONED, new LinkedList<>());
    }

    public RequestStatus requestStatus() {
        return requestStatus;
    }
    public LinkedList<Trade> trades() {
        return trades;
    }


}
