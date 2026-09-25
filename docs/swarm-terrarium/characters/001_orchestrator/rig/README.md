# 001 Orchestrator — puppet rig

This is the first-pass 2D puppet skeleton for the redesigned node-creature anatomy.

## Structure

- central body root
- independent left/right eye controls
- five asymmetric dendrite chains
- mid-tendril branch bones
- underside gestation chain
- two intermediate gestation pods
- enlarged terminal egg
- spring parameters for dendrite and gestation motion

`rig.json` uses normalized coordinates so the skeleton survives later image/layer replacement.

## Next pass

After the artwork is decomposed into layers, bind the generated layers to these bones and replace approximate anchors with pixel-fit anchors.